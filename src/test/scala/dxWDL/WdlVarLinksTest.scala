package dxWDL

import org.scalatest.{BeforeAndAfterEach, FlatSpec}
import spray.json._
import spray.json.DefaultJsonProtocol
import wdl4s.types._
import wdl4s.values._

class WdlVarLinksTest extends FlatSpec with BeforeAndAfterEach {

    it should "import JSON values" in {
        val x = WdlVarLinks.apply(WdlBooleanType, JsBoolean(true))
        System.err.println(x)

        val y = WdlVarLinks.apply(WdlArrayType(WdlIntegerType),
                                  JsArray(Vector(JsNumber(1), JsNumber(2.3))))
        System.err.println(y)

        val z = WdlVarLinks.apply(WdlArrayType(WdlStringType),
                                  JsArray(Vector(JsString("hello"), JsString("sunshine"), JsString("ride"))))
        System.err.println(z)
    }
}
