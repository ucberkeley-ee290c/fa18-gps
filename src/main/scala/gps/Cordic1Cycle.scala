package gps

import chisel3._
import chisel3.experimental.FixedPoint

import chisel3.util.Decoupled
import dsptools.numbers._

import chisel3.util._
import breeze.numerics.constants.Pi
import dsptools.numbers._
import scala.math._


/**
 * Base class for CORDIC parameters
 *
 * These are type generic
 */
trait CordicParams[T <: Data] {
  val protoXY: T
  val protoZ: T
  val nStages: Int
  val correctGain: Boolean
}

/**
 * CORDIC parameters object for fixed-point CORDICs
 */
case class FixedCordicParams(
  // width of X and Y
  xyWidth: Int,
  xyBPWidth: Int,
  // width of Z
  zWidth: Int,
  zBPWidth: Int,
  // number of stages
  nStages: Int,
  // scale output by correction factor?
  correctGain: Boolean = true,
) extends CordicParams[FixedPoint] {
  // prototype for x and y
  // binary point is (xyWidth-2) to represent 1.0 exactly
  val protoXY = FixedPoint(xyWidth.W, xyBPWidth.BP)
  // prototype for z
  // binary point is (xyWidth-2) to represent Pi/2 exactly
  val protoZ = FixedPoint(zWidth.W, zBPWidth.BP)
}

/**
 * Bundle type that describes the input, state, and output of CORDIC
 */
class CordicBundle[T <: Data](params: CordicParams[T]) extends Bundle {
  val x: T = params.protoXY.cloneType
  val y: T = params.protoXY.cloneType
  val z: T = params.protoZ.cloneType
  override def cloneType: this.type = CordicBundle(params).asInstanceOf[this.type]
}
object CordicBundle {
  def apply[T <: Data](params: CordicParams[T]): CordicBundle[T] = new CordicBundle(params)
}

/**
 * Bundle type as IO for iterative CORDIC modules
 */
class IterativeCordicIO[T <: Data](params: CordicParams[T]) extends Bundle {
  val in = Input(CordicBundle(params))
  val out = Output(CordicBundle(params))
  val vectoring = Input(Bool())
  val dividing = Input(Bool())

  override def cloneType: this.type = IterativeCordicIO(params).asInstanceOf[this.type]
}
object IterativeCordicIO {
  def apply[T <: Data](params: CordicParams[T]): IterativeCordicIO[T] =
    new IterativeCordicIO(params)
}

object AddSub {
  def apply[T <: Data : Ring](sel: Bool, a: T, b: T): T = {
    Mux(sel, a + b, a - b)
  }
}

/**
 * Cordic design, a pure combinational logic, finish calculation in 1 cyecle
 */
class Cordic1Cycle[T <: Data : Real : BinaryRepresentation](val params: CordicParams[T]) extends Module {
  val io = IO(IterativeCordicIO(params))

  // get intermediate wires
  val xMid = Wire(Vec(params.nStages+1, params.protoXY.cloneType))  // use Seq?
  val yMid = Wire(Vec(params.nStages+1, params.protoXY.cloneType))
  val zMid = Wire(Vec(params.nStages+1, params.protoZ.cloneType))

  // get constants
  val const = CordicConstants
  val linear = const.linear(0, params.nStages)
  val gain = ConvertableTo[T].fromDouble(1/const.gain(params.nStages))
  val arctan = VecInit(const.arctan(params.nStages).map(ConvertableTo[T].fromDouble(_)))

  // get initial map
  when(io.vectoring){   // vectoring mode
    when((io.in.y<Ring[T].zero) && (io.in.x<Ring[T].zero)){
        xMid(0) := -io.in.y
        yMid(0) := io.in.x
        zMid(0) := io.in.z - ConvertableTo[T].fromDouble(Pi/2)
    }.elsewhen((io.in.y>Ring[T].zero) && (io.in.x<Ring[T].zero)){
        xMid(0) := io.in.y
        yMid(0) := -io.in.x
        zMid(0) := io.in.z + ConvertableTo[T].fromDouble(Pi/2)
    }.otherwise{
        xMid(0) := io.in.x
        yMid(0) := io.in.y
        zMid(0) := io.in.z
    }
  }.otherwise { // rotation mode
    when(io.in.z > ConvertableTo[T].fromDouble(Pi / 2)) {
      xMid(0) := -io.in.y
      yMid(0) := io.in.x
      zMid(0) := io.in.z - ConvertableTo[T].fromDouble(Pi / 2)
    }.elsewhen(io.in.z < -1 * ConvertableTo[T].fromDouble(Pi / 2)) {
      xMid(0) := io.in.y
      yMid(0) := -io.in.x
      zMid(0) := io.in.z + ConvertableTo[T].fromDouble(Pi / 2)
    }.otherwise {
      xMid(0) := io.in.x
      yMid(0) := io.in.y
      zMid(0) := io.in.z
    }
  }

  // core calculation
  when(io.vectoring) {  // vectoring mode
    for(i <- 0 until params.nStages) {
      xMid(i+1) := AddSub(yMid(i) <= Ring[T].zero, xMid(i), -yMid(i)>>(i) )
      yMid(i+1) := AddSub(yMid(i) <= Ring[T].zero, yMid(i), xMid(i)>>(i) )
      zMid(i+1) := AddSub(yMid(i) <= Ring[T].zero, zMid(i), -arctan(i) )
    }
  }.otherwise{  //rotation mode
    for(i <- 0 until params.nStages) {
      xMid(i+1) := AddSub(zMid(i) > Ring[T].zero, xMid(i), -yMid(i)>>(i) )
      yMid(i+1) := AddSub(zMid(i) > Ring[T].zero, yMid(i), xMid(i)>>(i) )
      zMid(i+1) := AddSub(zMid(i) > Ring[T].zero, zMid(i), -arctan(i) )
    }
  }

  // gain correcting
  if (params.correctGain){
    io.out.x := gain*xMid(params.nStages)
    io.out.y := gain*yMid(params.nStages)
  }else{
    io.out.x := xMid(params.nStages)
    io.out.y := xMid(params.nStages)
  }
  io.out.z := zMid(params.nStages)

}





