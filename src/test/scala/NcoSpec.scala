package gps

import dsptools.numbers._
import org.scalatest.{FlatSpec, Matchers}
import breeze.numerics.{cos, sin, pow}
import breeze.numerics.constants._

class NcoSpec extends FlatSpec with Matchers {
  behavior of "FixedNCO"

  val params = new NcoParams[DspReal]{ 
    val proto = DspReal()
    val width = 3
    val sinOut = false
  }

  it should "produce cosines" in {
    val input = Seq(1, 2, 3, 4, 5, 6, 7, 7, 1, 2, 3, 4, 5, 4, 5, 6, 7, 0, 2)
    val output = (input.scanLeft(0)(_ + _)).map(x => cos(2.0*Pi*x/pow(2, params.width)))
    NcoTester(params, input, output) should be (true)
  }

}
