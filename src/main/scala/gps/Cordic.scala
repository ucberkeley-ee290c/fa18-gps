package gps

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.util.{log2Ceil, Decoupled}

import dsptools.numbers._
import scala.math._


// xy integer bits should be more than 2 + log2(max_input)

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
  val stagesPerCycle: Int
  val calAtan2: Boolean
  val dividing: Boolean
  // requireIsHardware(protoXY)
  // requireIsHardware(protoZ)
}

case class RealCordicParams(
  nStages:Int = 50,
  correctGain: Boolean = true,
  stagesPerCycle: Int = 1,
  dividing: Boolean = false,
  calAtan2: Boolean = false,
) extends CordicParams[DspReal] {
  val protoXY = DspReal()
  val protoZ = DspReal()
}

case class FixedCordicParams(
  xyWidth: Int,
  xyBPWidth: Int,
  zWidth: Int,
  zBPWidth: Int,
  correctGain: Boolean = true,
  stagesPerCycle: Int = 1,
  calAtan2: Boolean = true,
  dividing: Boolean = false,
  nStages: Int
) extends CordicParams[FixedPoint] {
  val protoXY = FixedPoint(xyWidth.W, xyBPWidth.BP)
  val protoZ = FixedPoint(zWidth.W, zBPWidth.BP)
  print("Z Width: ")
  print(zWidth)
  print(" ")
  print(zBPWidth)
  println()
}

class CordicBundle[T <: Data](val params: CordicParams[T]) extends Bundle {
  val x: T = params.protoXY.cloneType
  val y: T = params.protoXY.cloneType
  val z: T = params.protoZ.cloneType

  override def cloneType: this.type = CordicBundle(params).asInstanceOf[this.type]
}
object CordicBundle {
  def apply[T <: Data](params: CordicParams[T]): CordicBundle[T] = new CordicBundle(params)
}

class IterativeCordicIO[T <: Data](params: CordicParams[T]) extends Bundle {
  val in = Flipped(Decoupled(CordicBundle(params)))
  val out = Decoupled(CordicBundle(params))

  val vectoring = Input(Bool())

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
  * The main part of the cordic algorithm expects to see vectors in the 1st and 4th quadrant (or angles with absolute
  * value < pi/2).
  *
  * This function transforms inputs into ranges that the main part of the cordic can deal with.
  */
object TransformInput {
  def apply[T <: Data : Real : BinaryRepresentation](xyz: CordicBundle[T], vectoring: Bool): CordicBundle[T] = {
    val pi = ConvertableTo[T].fromDouble(math.Pi)
    val piBy2 = ConvertableTo[T].fromDouble(math.Pi/2)
    val zBig = xyz.z >= piBy2
    val zSmall = xyz.z <= -piBy2
    val xNeg = xyz.x.isSignNegative()
    val yNeg = xyz.y.isSignNegative()


    val xyzTransformed = WireInit(xyz)
    if (xyz.params.dividing){
      when(xNeg){
        xyzTransformed.x := -xyz.x
      }.otherwise{
        xyzTransformed.x := xyz.x
      }
      when(yNeg) {
        xyzTransformed.y := -xyz.y 
      }.otherwise{
        xyzTransformed.y := xyz.y 
      }
      xyzTransformed.z := xyz.z
    }else {
      when(vectoring) {
        // When vectoring, if in quadrant 2 or 3 we rotate by pi
        when(xNeg) {
          xyzTransformed.x := -xyz.x
          xyzTransformed.y := -xyz.y
          if (xyz.params.calAtan2) {
            // if calculate atan2
            when(yNeg) {
              // if yNeg, then transformed y is positive
              // we'll have a positive z, so subtract pi
              xyzTransformed.z := xyz.z - pi
            }.otherwise {
              xyzTransformed.z := xyz.z + pi
            }
          } else {
            // if calculate atan
            xyzTransformed.z := xyz.z
          }
        }
      }.otherwise {
        // when rotating, if |z| > pi/2 rotate by pi/2 so |z| < pi/2
        when(zBig) {
          xyzTransformed.x := -xyz.y
          xyzTransformed.y := xyz.x
          xyzTransformed.z := xyz.z - piBy2
        }
        when(zSmall) {
          xyzTransformed.x := xyz.y
          xyzTransformed.y := -xyz.x
          xyzTransformed.z := xyz.z + piBy2
        }
      }
    }
    xyzTransformed
  }
}

class CordicStage[T <: Data : Real : BinaryRepresentation](params: CordicParams[T]) extends Module {
  val io = IO(new Bundle {
    val in = Input(CordicBundle(params))
    val vectoring = Input(Bool())
    val shift = Input(UInt(params.nStages.U.getWidth.W))
    val romIn = Input(params.protoZ.cloneType)
    val out = Output(CordicBundle(params))
  })
  val xshift = io.in.x >> io.shift
  val yshift = io.in.y >> io.shift

  val d = Mux(io.vectoring,
    io.in.y.signBit(),
    !io.in.z.signBit()
  )

  io.out.y := AddSub(d, io.in.y, xshift)
  if (!params.dividing) {
    io.out.x := AddSub(~d, io.in.x, yshift)
    io.out.z := AddSub(~d, io.in.z, io.romIn)
  }else{
    io.out.x := io.in.x
    io.out.z := AddSub(~d, io.in.z, io.romIn)
  }
}


/*
 * IF IN DIVIDING MODE, |io.xyz.y| < 2*|io.xyz.x|!!!!!
 */ 
class FixedIterativeCordic[T <: Data : Real : BinaryRepresentation](val params: CordicParams[T]) extends Module {
  require(params.nStages > 0)
  require(params.stagesPerCycle > 0)
  require(params.nStages >= params.stagesPerCycle)
  require(params.nStages % params.stagesPerCycle == 0, "nStages must be multiple of stagesPerCycles")


  val io = IO(IterativeCordicIO(params))

  // Make states for state machine
  val sInit = 0.U(2.W)
  val sWork = 1.U(2.W)
  val sDone = 2.U(2.W)
  val state = RegInit(sInit)

  // Register to hold iterations of CORDIC
  val xyz = Reg(CordicBundle(params))
  // Counter for the current iteration
  val iter = RegInit(0.U(log2Ceil(params.nStages + 1).W))

  val gain = params.protoXY.fromDouble(1 / CordicConstants.gain(params.nStages))

  // get table
  val table =
    if (!params.dividing) {
      print(params.nStages)
      println()
      print(CordicConstants.arctan(params.nStages))
      println()
      CordicConstants.arctan(params.nStages)
    } else{
      CordicConstants.linear(params.nStages)
    }

  // put in rom
  val rom =
    if (!params.dividing) {
      VecInit(table.map(params.protoZ.fromDouble(_)))
    }else{
      VecInit(table.map(params.protoZ.fromDouble(_))) // may need change
    }
  val regVectoring = Reg(Bool())

  // Make the stages and connect everything except in and out
  val stages = for (i <- 0 until params.stagesPerCycle) yield {
    val idx = iter + i.U
    val stage = Module(new CordicStage(params))
    stage.io.vectoring := regVectoring
    stage.io.shift := idx
    stage.io.romIn := rom(idx)
    stage
  }
  // Chain the stages together
  val stageOut = stages.foldLeft(xyz) { case (in, stage) =>
      stage.io.in := in
      stage.io.out
  }

  // FSM
  when (state === sInit && io.in.fire()) {
    state := sWork
    iter := 0.U
    regVectoring := io.vectoring

    xyz := TransformInput(io.in.bits, io.vectoring)
  }
  // FIXME Cordic broken!!!, need to end early if y = 0 in vectoring mode!
  when (state === sWork) {
    val iterNext = iter + params.stagesPerCycle.U
    iter := iterNext
    when (iterNext >= (params.nStages - 1).U) {
      state := sDone
    }
    xyz := stageOut
  }
  when (state === sDone && io.out.fire()) {
    state := sInit
  }

  io.in.ready := state === sInit
  io.out.valid := state === sDone

  if (params.correctGain && !params.dividing) {
    io.out.bits.x := xyz.x * gain
    io.out.bits.y := xyz.y * gain
  } else {
    io.out.bits.x := xyz.x
    io.out.bits.y := xyz.y
  }

  // check negtive
  val xNeg = io.in.bits.x.isSignNegative()
  val yNeg = io.in.bits.y.isSignNegative()
  val divNeg = xNeg^yNeg
  if (params.dividing) {
    when(divNeg) {
      io.out.bits.z := -xyz.z
    }.otherwise {
      io.out.bits.z := xyz.z
    }
  }else{
    io.out.bits.z := xyz.z
  }
}
