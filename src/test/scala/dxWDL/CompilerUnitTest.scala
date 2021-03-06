package dxWDL

import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths, Files}
import org.scalatest.{BeforeAndAfterEach, FlatSpec, OneInstancePerTest}
import scala.sys.process._
import spray.json._
import spray.json.DefaultJsonProtocol
//import spray.json.JsString
import wdl4s.{AstTools, Call, Task, WdlExpression, WdlNamespace, WdlNamespaceWithWorkflow, Workflow}
import wdl4s.AstTools.EnhancedAstNode
import wdl4s.types._
import wdl4s.values._

class CompilerUnitTest extends FlatSpec with BeforeAndAfterEach {

    // Look for a call inside a namespace.
    private def getCallFromNamespace(ns : WdlNamespaceWithWorkflow, callName : String ) : Call = {
        val wf: Workflow = ns.workflow
        wf.findCallByName(callName) match {
            case None => throw new AppInternalException(s"Call ${callName} not found in WDL file")
            case Some(call) => call
        }
    }

    // TODO
    //
    // These tests require compilation -without- access to the platform.
    // We need to split the compiler into front/back-ends to be able to
    // do this.
    /*"Compiler" should "Allow adding unbound argument" in {
        val wdl = """|task mul2 {
                     |    Int i
                     |
                     |    command {
                     |        python -c "print(${i} + ${i})"
                     |    }
                     |    output {
                     |        Int result = read_int(stdout())
                     |    }
                     |}
                     |
                     |workflow optionals {
                     |    Int arg1
                     |
                     |    # A call missing a compulsory argument
                     |    call mul2
                     |    output {
                     |        mul2.result
                     |    }
                     |}""".stripMargin.trim

        val ns = WdlNamespaceWithWorkflow.load(wdl)
        val call : Call = getCallFromNamespace(ns, "mul2")
        val inputs : Map[String,WdlValue] = Map("i" -> WdlInteger(3))
        val outputs : Seq[(String, WdlType, WdlValue)] = evalCall(call, inputs)
    }*/

    "Compiler" should "Report a useful error for a missing reference" in {
        val wdl = """|task mul2 {
                     |    Int i
                     |
                     |    command {
                     |        python -c "print(${i} + ${i})"
                     |    }
                     |    output {
                     |        Int result = read_int(stdout())
                     |    }
                     |}
                     |
                     |workflow ngs {
                     |    Int A
                     |    Int B
                     |    call mul2 { input: i=C }
                     |}
                     |""".stripMargin.trim
    }

    it should "figure out the type of scatter collection" in {
        val wdl = """|task prepare {
                     |  command <<<
                     |    python -c "print('one\ntwo\nthree\nfour')"
                     |   >>>
                     |   output {
                     |     Array[String] array = read_lines(stdout())
                     |   }
                     |}
                     |
                     |task analysis {
                     |   String str
                     |     command <<<
                     |       python -c "print('_${str}_')"
                     |     >>>
                     |   output {
                     |     String out = read_string(stdout())
                     |   }
                     |}
                     |
                     |workflow sg1 {
                     |   call prepare
                     |   scatter (x in prepare.array) {
                     |     call analysis {input: str=x}
                     |   }
                     |}""".stripMargin.trim

        val ns = WdlNamespaceWithWorkflow.load(wdl, Seq.empty).get
        val wf = ns.workflow
        val scatters = wf.scatters
        assert (scatters.size == 1)
        val scatter = scatters.toList.head

        val env : Map[String, WdlType] = Map("prepare.array" -> WdlArrayType(WdlStringType))
        val wdlType : WdlType = CompilerFrontEnd.calcIterWdlType(scatter, env)
        assert(wdlType == WdlStringType)
    }

    it should "Handle array access" in {
        val wdl = """|task diff {
                     |  File A
                     |  File B
                     |  command {
                     |    diff ${A} ${B} | wc -l
                     |  }
                     |  output {
                     |    Int result = read_int(stdout())
                     |  }
                     |}
                     |
                     |workflow file_array {
                     |  Array[File] fs
                     |  call diff {
                     |    input : A=fs[0], B=fs[1]
                     |  }
                     |  output {
                     |    diff.result
                     |  }
                     |}""".stripMargin.trim

        val ns = WdlNamespaceWithWorkflow.load(wdl, Seq.empty).get
        val wf: Workflow = ns.workflow
        val call : Call = getCallFromNamespace(ns, "diff")

        //var env : Compile.CallEnv = Map.empty[String, WdlVarLinks]
/*        var closure = Map.empty[String, WdlVarLinks]
        call.inputMappings.foreach { case (_, expr) =>
            System.err.println(s"${expr} --> ${expr.ast}")
            closure = Compile.updateClosure(wf, closure, env, expr, true)
        }*/
    }

    it should "Provide proper error code for unsupported types" in {
        val wdl = """|workflow foo {
                     |  Array[Array[Array[Int]]] aaai
                     |}"""
    }


    it should "Provide proper error code for declarations inside scatters" in {
        val wdl = """|task inc {
                     | Int i
                     | command <<<
                     |    python -c "print(${i} + 1)"
                     | >>>
                     | output {
                     |   Int result = read_int(stdout())
                     | }
                     |
                     | workflow a1 {
                     |   Array[Int] integers
                     |
                     |  scatter (i in integers) {
                     |    call inc {input: i=i}
                     |
                     |    # declaration in the middle of a scatter should cause an exception
                     |    String s = "abc"
                     |    call inc as inc2 {input: i=i}
                     |}"""
    }

    def compareIgnoreWhitespace(a: String, b:String): Boolean = {
        val retval = (a.replaceAll("\\s+", "") == b.replaceAll("\\s+", ""))
        if (!retval) {
            System.err.println("--- String comparison failed ---")
            System.err.println(s"${a}")
            System.err.println("---")
            System.err.println(s"${b}")
            System.err.println("---")
        }
        retval
    }

    it should "Pretty print declaration" in {
        val wdl = "Array[Int] integers"
        val ns = WdlNamespace.loadUsingSource(wdl, None, None).get
        val decl = ns.declarations.head
        val strWdlCode = WdlPrettyPrinter(true).apply(decl, 0).mkString("\n")
        assert(compareIgnoreWhitespace(strWdlCode, wdl))
    }

    it should "Pretty print workflow" in {
        val wdl = """|task inc {
                     |  Int i
                     |
                     |  command <<<
                     |     python -c "print(${i} + 1)"
                     |  >>>
                     |  output {
                     |    Int result = read_int(stdout())
                     |  }
                     |}
                     |
                     |workflow sg_sum3 {
                     |  Array[Int] integers
                     |
                     |  scatter (k in integers) {
                     |    call inc {input: i=k}
                     |  }
                     |}""".stripMargin.trim

        val ns = WdlNamespace.loadUsingSource(wdl, None, None).get
        val wf = ns match {
            case nswf: WdlNamespaceWithWorkflow => nswf.workflow
            case _ => throw new Exception("WDL file contains no workflow")
        }
        val strWdlCode = WdlPrettyPrinter(true).apply(wf, 0).mkString("\n")
        //assert(compareIgnoreWhitespace(strWdlCode, wdl))
    }
}
