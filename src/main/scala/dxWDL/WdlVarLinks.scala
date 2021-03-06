package dxWDL

// DX bindings
import com.dnanexus.{DXApplet, DXFile, DXJob, DXProject, DXWorkflow}
import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.HashMap
import spray.json._
import spray.json.DefaultJsonProtocol
import spray.json.JsString
import wdl4s.parser.WdlParser.{Ast, Terminal}
import wdl4s.types._
import wdl4s.values._

object IORef extends Enumeration {
    val Input, Output = Value
}

// A union of all the different ways of building a value
// from JSON passed by the platform.
//
// Complex values are WDL values that have files embedded in them. For example:
// - Ragged file array:  Array[Array[File]]
// - Object with file elements
// - Map of files:     Map[String, File]
// A complex value is implemented as a json structure, and all the files it references.
sealed trait DxLink
case class DxlJsValue(jsn: JsValue) extends DxLink
case class DxlComplexValue(jsn: JsValue, files: Vector[DXFile]) extends DxLink
case class DxlStage(dxStage: DXWorkflow.Stage, ioRef: IORef.Value, varName: String) extends DxLink
case class DxlJob(dxJob: DXJob, ioRef: IORef.Value, varName: String) extends DxLink

case class WdlVarLinks(wdlType: WdlType, dxlink: DxLink)

// Bridge between WDL values and DNAx values.
case class BValue(wvl: WdlVarLinks, wdlValue: WdlValue)

object WdlVarLinks {
    // A dictionary of all WDL files that map to platform files,
    // that exist on the current cloud instance.
    var localDxFiles = HashMap.empty[Path, DXFile]

    // Parse a dnanexus file descriptor. Examples:
    //
    // "$dnanexus_link": {
    //    "project": "project-BKJfY1j0b06Z4y8PX8bQ094f",
    //    "id": "file-BKQGkgQ0b06xG5560GGQ001B"
    //   }
    //
    //  {"$dnanexus_link": "file-F0J6JbQ0ZvgVz1J9q5qKfkqP"}
    //
    private def dxFileOfJsValue(jsValue : JsValue) : DXFile = {
        val innerObj = jsValue match {
            case JsObject(fields) =>
                fields.get("$dnanexus_link") match {
                    case None => throw new AppInternalException(s"Bad json of dnanexus link $jsValue")
                    case Some(x) => x
                }
            case  _ =>
                throw new AppInternalException(s"Bad json of dnanexus link $jsValue")
        }

        val (fid, projId) : (String, Option[String]) = innerObj match {
            case JsString(fid) =>
                // We just have a file-id
                (fid, None)
            case JsObject(linkFields) =>
                // file-id and project-id
                val fid =
                    linkFields.get("id") match {
                        case Some(JsString(s)) => s
                        case _ => throw new AppInternalException(s"No file ID found in dnanexus link $jsValue")
                    }
                linkFields.get("project") match {
                    case Some(JsString(pid : String)) => (fid, Some(pid))
                    case _ => (fid, None)
                }
            case _ =>
                throw new AppInternalException(s"Could not parse a dxlink from $innerObj")
        }

        projId match {
            case None => DXFile.getInstance(fid)
            case Some(pid) => DXFile.getInstance(fid, DXProject.getInstance(pid))
        }
    }

    private def wdlFileOfDxLink(jsValue: JsValue, force: Boolean) : WdlValue = {
        // Download the file, and place it in a local file, with the
        // same name as the platform. All files have to be downloaded
        // into the same directory; the only exception we make is for
        // disambiguation purposes.
        val dxFile = dxFileOfJsValue(jsValue)
        val fName = dxFile.describe().getName()
        val shortPath = Utils.inputFilesDirPath.resolve(fName)
        val path : Path =
            if (Files.exists(shortPath)) {
                // Short path already exists. Note: this check is brittle in the case
                // of concurrent downloads.
                val fid = dxFile.getId()
                System.err.println(s"Disambiguating file ${fid} with name ${fName}")
                val dir:Path = Utils.inputFilesDirPath.resolve(fid)
                Utils.safeMkdir(dir)
                Utils.inputFilesDirPath.resolve(fid).resolve(fName)
            } else {
                shortPath
            }
        localDxFiles.get(path) match {
            case None =>
                if (force) {
                    // Download right now
                    Utils.downloadFile(path, dxFile)
                } else {
                    // Create an empty file, to mark the fact that the path and
                    // file name are in use. We may not end up downloading the
                    // file, and accessing the data, however, we need to keep
                    // the path in the WdlFile value unique.
                    Files.createFile(path)
                    DxFunctions.registerRemoteFile(path.toString, dxFile)
                }
                localDxFiles(path) = dxFile
            case Some(dxFile) =>
                // we have already downloaded the file
                ()
        }
        WdlSingleFile(path.toString)
    }


    private def evalCore(wdlType: WdlType, jsValue: JsValue, force: Boolean) : WdlValue = {
        (wdlType, jsValue)  match {
            // base case: primitive types
            case (WdlBooleanType, JsBoolean(b)) => WdlBoolean(b.booleanValue)
            case (WdlIntegerType, JsNumber(bnm)) => WdlInteger(bnm.intValue)
            case (WdlFloatType, JsNumber(bnm)) => WdlFloat(bnm.doubleValue)
            case (WdlStringType, JsString(s)) => WdlString(s)
            case (WdlFileType, _) => wdlFileOfDxLink(jsValue, force)

            // arrays
            case (WdlArrayType(t), JsArray(vec)) =>
                val wVec: Seq[WdlValue] = vec.map{
                    elem:JsValue => evalCore(t, elem, force)
                }
                WdlArray(WdlArrayType(t), wVec)

                // TODO
            //case (WdlMapType(keyType, valueType), WdlMap()) =>
            //case (WdlPairType, WdlPair())
            //case (WdlObjectType, WdlObject())
            case _ =>
                throw new AppInternalException(
                    s"Unsupport combination ${wdlType.toWdlString} ${jsValue.prettyPrint}"
                )
        }
    }

    // Calculate a WdlValue from the dx-links structure. If [force] is true,
    // any files included in the structure will be downloaded.
    def eval(wvl: WdlVarLinks, force: Boolean) : WdlValue = {
        val jsValue: JsValue = wvl.dxlink match {
            case DxlJsValue(jsn) => jsn
            case DxlComplexValue(jsn,_) => jsn
            case _ =>
                throw new AppInternalException(s"Unsupported conversion from ${wvl.dxlink} to WdlValue")
        }
        evalCore(wvl.wdlType, jsValue, force)
    }

    private def jsStringLimited(buf: String) : JsValue = {
        if (buf.length > Utils.MAX_STRING_LEN)
            throw new AppInternalException(s"string is longer than ${Utils.MAX_STRING_LEN}")
        JsString(buf)
    }

    // Serialize a complex WDL value into a JSON value. The value could potentially point
    // to many files. Serialization proceeds as follows:
    // 1. Make a pass on the object, upload any files, and keep an in-memory JSON representation
    // 2. In memory we have a, potentially very large, JSON value. Upload it to the platform
    //    as a file, and return the JSON of that file.
    private def jsOfComplexWdlValue(wdlType: WdlType, wdlValue: WdlValue) : (JsValue, Vector[DXFile]) = {
        def uploadFile(path: Path) : (JsValue, Vector[DXFile]) =  {
            localDxFiles.get(path) match {
                case None =>
                    val dxLink = Utils.uploadFile(path)
                    val dxFile = dxFileOfJsValue(dxLink)
                    (dxLink, Vector(dxFile))
                case Some(dxFile) =>
                    val dxLink = Utils.jsValueOfJsonNode(dxFile.getLinkAsJson)
                    (dxLink, Vector(dxFile))
            }
        }

        (wdlType, wdlValue) match {
            // Base case: primitive types
            case (WdlFileType, WdlString(path)) => uploadFile(Paths.get(path))
            case (WdlFileType, WdlSingleFile(path)) => uploadFile(Paths.get(path))
            case (WdlStringType, WdlSingleFile(path)) => (JsString(path), Vector.empty[DXFile])
            case (_,WdlBoolean(b)) => (JsBoolean(b), Vector.empty[DXFile])
            case (_,WdlInteger(n)) => (JsNumber(n), Vector.empty[DXFile])
            case (_,WdlFloat(x)) => (JsNumber(x), Vector.empty[DXFile])
            case (_,WdlString(s)) => (jsStringLimited(s), Vector.empty[DXFile])

            // Base case: empty array
            case (_, WdlArray(_, ar)) if ar.length == 0 =>
                (JsArray(Vector.empty), Vector.empty[DXFile])

            // Non empty array
            case (WdlArrayType(t), WdlArray(_, elems)) =>
                val (jsVals, dxFiles) = elems.map(e => jsOfComplexWdlValue(t, e)).unzip
                (JsArray(jsVals.toVector), dxFiles.toVector.flatten)

                // TODO
            //case (WdlMapType(keyType, valueType), WdlMap()) =>
            //case (WdlPairType, WdlPair())
            //case (WdlObjectType, WdlObject())

            case _ => throw new Exception(
                s"Unsupported WDL type ${wdlType.toWdlString} ${wdlValue.toWdlString}"
            )
        }
    }

    private def stripArrayVal(x : WdlValue) : Seq[WdlValue] = {
        x match {
            case WdlArray(_,l) => l
            case _ => throw new AppInternalException(s"wdl value is not an array ${x.toWdlString}")
        }
    }

    // import a WDL value
    def apply(wdlTypeOrg: WdlType, wdlValue: WdlValue, dbg: String = "") : WdlVarLinks = {
        // Strip optional types
        val wdlType = Utils.stripOptional(wdlTypeOrg)
        def genDbgFileName(): String =
            if (dbg == "") wdlType.toWdlString
            else dbg + "_" + wdlType.toWdlString
        wdlType match {
            case WdlBooleanType | WdlIntegerType | WdlFloatType | WdlStringType | WdlFileType =>
                val (js, _) = jsOfComplexWdlValue(wdlType, wdlValue)
                WdlVarLinks(wdlTypeOrg, DxlJsValue(js))

            case WdlArrayType(WdlBooleanType)
                   | WdlArrayType(WdlIntegerType)
                   | WdlArrayType(WdlFloatType)
                   | WdlArrayType(WdlStringType)
                   | WdlArrayType(WdlFileType) =>
                val (js,_) = jsOfComplexWdlValue(wdlType, wdlValue)
                WdlVarLinks(wdlTypeOrg, DxlJsValue(js))

            // ragged arrays of primitive types.
            // convert to JSON, marshal into a string, and
            // upload as a file. Return a file ID.
            case WdlArrayType(WdlArrayType(WdlBooleanType))
                   | WdlArrayType(WdlArrayType(WdlIntegerType))
                   | WdlArrayType(WdlArrayType(WdlFloatType))
                   | WdlArrayType(WdlArrayType(WdlStringType)) =>
                val t:WdlType = Utils.stripArray(wdlType)
                val l = stripArrayVal(wdlValue)
                val raggedAr: JsValue = JsArray(
                    l.toVector.map{ case x =>
                        val (js, _) = jsOfComplexWdlValue(t, x)
                        js
                    })
                val buf = raggedAr.prettyPrint
                val jsSrlFile = Utils.uploadString(buf, genDbgFileName())
                WdlVarLinks(wdlTypeOrg, DxlJsValue(jsSrlFile))

            // Complex values, that may have files in them. For example, ragged file arrays.
            case _ =>
                val (jsVal,dxFiles) = jsOfComplexWdlValue(wdlType, wdlValue)
                val buf = jsVal.prettyPrint
                val jsSrlFile = Utils.uploadString(buf, genDbgFileName())
                WdlVarLinks(wdlTypeOrg, DxlComplexValue(jsSrlFile, dxFiles))
        }
    }

    // Search through a JSON value for all the dx:file links inside it. Returns
    // those as a vector.
    def findDxFiles(jsSrlVal: JsValue) : Vector[DXFile] = {
        jsSrlVal match {
            case JsBoolean(_) | JsNull | JsNumber(_) | JsString(_) =>
                Vector.empty[DXFile]
            case JsObject(fields) =>
                fields.map{ case(k,v) => findDxFiles(v) }.toVector.flatten
            case JsArray(elems) =>
                elems.map(e => findDxFiles(e)).flatten
        }
    }

    // Convert an input field to a dx-links structure. This allows
    // passing it to other jobs.
    def apply(wdlType: WdlType, jsValue: JsValue) : WdlVarLinks = {
        wdlType match {
            case WdlBooleanType | WdlIntegerType | WdlFloatType | WdlStringType | WdlFileType
                   | WdlArrayType(WdlBooleanType)
                   | WdlArrayType(WdlIntegerType)
                   | WdlArrayType(WdlFloatType)
                   | WdlArrayType(WdlStringType)
                   | WdlArrayType(WdlFileType) =>
                // This is primitive value, or a single dimensional
                // array of primitive values.
                WdlVarLinks(wdlType, DxlJsValue(jsValue))

            case WdlArrayType(WdlArrayType(WdlBooleanType))
                   | WdlArrayType(WdlArrayType(WdlIntegerType))
                   | WdlArrayType(WdlArrayType(WdlFloatType))
                   | WdlArrayType(WdlArrayType(WdlStringType)) =>
                // ragged array. Download the file holding the data,
                // and unmarshal it.
                val dxfile = dxFileOfJsValue(jsValue)
                val buf = Utils.downloadString(dxfile)
                val raggedAr: JsValue = buf.parseJson
                WdlVarLinks(wdlType, DxlJsValue(raggedAr))

                // complex types
            case _ =>
                val dxfile = dxFileOfJsValue(jsValue)
                val buf = Utils.downloadString(dxfile)
                val jsSrlVal:JsValue = buf.parseJson
                val dxFiles = findDxFiles(jsSrlVal)
                WdlVarLinks(wdlType, DxlComplexValue(jsSrlVal, dxFiles))
        }
    }

    // Open a WDL array into a sequence of elements. For example:
    // WdlArray(1, 2, 3) =>
    //     List(WdlInteger(1), WdlInteger(2), WdlInteger(3))
    // Each of the WDL values is represented as a WdlVarLinks structure.
    //
    // Note: the Wdl value must be an array.
    def unpackWdlArray(wvl: WdlVarLinks) : Seq[WdlVarLinks] = {
        val elemType : WdlType = Utils.stripArray(wvl.wdlType)
        wvl.dxlink match {
            case DxlJsValue(jsn) =>
                jsn match {
                    case JsArray(l) =>
                        l.map(elem => WdlVarLinks(elemType, DxlJsValue(elem)))
                    case t => throw new AppInternalException(s"Wrong type ${t} for json array")
                }
            case DxlComplexValue(jsn, _) =>
                jsn match {
                    case JsArray(l) =>
                        l.map(elem => WdlVarLinks(elemType, DxlComplexValue(elem, findDxFiles(elem))))
                    case t => throw new AppInternalException(s"Wrong type ${t} for json array")
                }
            case DxlStage(dxStage, _, _) =>
                throw new AppInternalException(s"Values must be unpacked, not dxStage ${dxStage}")
            case DxlJob(dxJob, _, _) =>
                throw new AppInternalException(s"Values must be unpacked, not dxJob ${dxJob}")
        }

    }

    // create input/output fields that bind the variable name [bindName] to
    // this WdlVar
    def genFields(wvl : WdlVarLinks, bindName: String) : List[(String, JsonNode)] = {
        val bindEncName = Utils.encodeAppletVarName(Utils.transformVarName(bindName))

        def mkSimple() : (String, JsonNode) = {
            val jsNode : JsonNode = wvl.dxlink match {
                case DxlStage(dxStage, ioRef, varEncName) =>
                    ioRef match {
                        case IORef.Input => dxStage.getInputReference(varEncName)
                        case IORef.Output => dxStage.getOutputReference(varEncName)
                    }
                case DxlJob(dxJob, ioRef, varEncName) =>
                    val jobId : String = dxJob.getId()
                    Utils.jsonNodeOfJsValue(Utils.makeJBOR(jobId, varEncName))
                case DxlJsValue(jsn) => Utils.jsonNodeOfJsValue(jsn)
                case DxlComplexValue(jsn, _) => Utils.jsonNodeOfJsValue(jsn)
            }
            (bindEncName, jsNode)
        }
        def mkComplex() : Map[String,JsonNode] = {
            val bindEncName_F = bindEncName + Utils.FLAT_FILES_SUFFIX
            wvl.dxlink match {
                case DxlStage(dxStage, ioRef, varEncName) =>
                    val varEncName_F = varEncName + Utils.FLAT_FILES_SUFFIX
                    ioRef match {
                        case IORef.Input => Map(
                            bindEncName -> dxStage.getInputReference(varEncName),
                            bindEncName_F -> dxStage.getInputReference(varEncName_F)
                        )
                        case IORef.Output => Map(
                            bindEncName -> dxStage.getOutputReference(varEncName),
                            bindEncName_F -> dxStage.getOutputReference(varEncName_F)
                        )
                    }
                case DxlJob(dxJob, ioRef, varEncName) =>
                    val varEncName_F = varEncName + Utils.FLAT_FILES_SUFFIX
                    val jobId : String = dxJob.getId()
                    Map(
                        bindEncName -> Utils.jsonNodeOfJsValue(Utils.makeJBOR(jobId, varEncName)),
                        bindEncName_F -> Utils.jsonNodeOfJsValue(Utils.makeJBOR(jobId, varEncName_F))
                    )
                case DxlJsValue(jsn) =>
                    Map(bindEncName -> Utils.jsonNodeOfJsValue(jsn),
                        bindEncName_F -> Utils.jsonNodeOfJsValue(JsArray(Vector.empty[JsValue])))
                case DxlComplexValue(jsn, dxFiles) =>
                    val jsFiles = dxFiles.map(x => Utils.jsValueOfJsonNode(x.getLinkAsJson))
                    Map(bindEncName -> Utils.jsonNodeOfJsValue(jsn),
                        bindEncName_F -> Utils.jsonNodeOfJsValue(JsArray(jsFiles.toVector)))
            }
        }

        Utils.stripOptional(wvl.wdlType) match {
            // Types that are supported natively in DX
            case WdlBooleanType | WdlIntegerType | WdlFloatType | WdlStringType| WdlFileType |
                    WdlArrayType(WdlBooleanType) |
                    WdlArrayType(WdlIntegerType) |
                    WdlArrayType(WdlFloatType) |
                    WdlArrayType(WdlStringType) |
                    WdlArrayType(WdlFileType) =>
                List(mkSimple())

                // Types that are implemented by packing into a, potentially large JSON
                // structure, and uploading as a file.
            case WdlArrayType(WdlArrayType(WdlBooleanType)) |
                    WdlArrayType(WdlArrayType(WdlIntegerType)) |
                    WdlArrayType(WdlArrayType(WdlFloatType)) |
                    WdlArrayType(WdlArrayType(WdlStringType)) =>
                List(mkSimple())

                // Complex types requiring two fields: a JSON structure, and a flat array of files.
            case _ =>
                mkComplex().toList
        }
    }


    // Read the job-inputs JSON file, and convert the variables
    // to links that can be passed to other applets.
    def loadJobInputsAsLinks(inputLines : String, closureTypes : Map[String, Option[WdlType]]) :
            Map[String, WdlVarLinks] = {
        // Read the job_inputs.json file. Convert it to a mapping from string to JSON
        // value.
        val jsonAst : JsValue = inputLines.parseJson
        val fields : Map[String, JsValue] = jsonAst.asJsObject.fields

        // Create a mapping from each key to its WDL value,
        // ignore all untyped fields.
        closureTypes.map { case (key,wdlTypeOpt) =>
            wdlTypeOpt match {
                case None => None
                case Some(WdlOptionalType(wType)) =>
                    fields.get(key) match {
                        case None => None
                        case Some(jsValue) =>
                            val wvl = apply(wType, jsValue)
                            Some(key -> wvl)
                    }
                case Some(wType) =>
                    val jsValue = fields(key)
                    val wvl = apply(wType, jsValue)
                    Some(key -> wvl)
            }
        }.flatten.toMap
    }
}
