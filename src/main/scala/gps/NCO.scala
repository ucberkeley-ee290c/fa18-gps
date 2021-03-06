package gps

import chisel3._
import chisel3.util._
import scala.math._

import dsptools.numbers._

/** Base class for NCO parameters
 *
 *  These are type generic
 *  @tparam T NCO output type
 */
trait NcoParams[T <: Data] {
  /** Output prototype */
  val proto: T
  /** NCO full register bit width */
  val resolutionWidth: Int
  /** NCO output bit width */ 
  val truncateWidth: Int
  /** Boolean that determines if the NCO outputs a sine wave */
  val sinOut: Boolean
  /** Boolean that determines if the NCO output has a scale-factor */ 
  val highRes: Boolean
}

/** NCO parameters object for an NCO with an SInt output
 *
 *  @param resolutionWidth NCO register bit width (full resolution)
 *  @param truncadeWidth NCO output bit width (after truncating)
 *  @param sinOut If true, NCO also has a sine output
 */
case class SIntNcoParams(
  resolutionWidth: Int,
  truncateWidth: Int,
  sinOut: Boolean,
  highRes: Boolean = false

) extends NcoParams[SInt] {
  /** Output prototype: SInt of width truncateWidth */
  val proto = SInt(truncateWidth.W)
}

/** Bundle type that describes an NCO that outputs both sine and cosine
 *  
 */
class NcoSinOutBundle[T <: Data](params: NcoParams[T]) extends Bundle {
  /** NCO accumulator input step size */
  val stepSize = Input(UInt(params.resolutionWidth.W))

  /** The NCO register output */ 
  val truncateRegOut = Output(UInt(params.truncateWidth.W))
  /** Output sine signal (if sinOut == true) */
  val sin: T = Output(params.proto.cloneType)
  /** Output cosinde signal */ 
  val cos: T = Output(params.proto.cloneType)
//  val softRst = Input(Bool())

  override def cloneType: this.type = NcoSinOutBundle(params).asInstanceOf[this.type]
}

/** Factory for [[gps.NcoSinOutBundle]] instances. */
object NcoSinOutBundle {
  /** Creates an NcoSinOutBundle with given set of params.
   *
   *  @param params The NCO parameters 
   */
  def apply[T <: Data](params: NcoParams[T]): NcoSinOutBundle[T] = new NcoSinOutBundle(params)
}

/** Bundle type that describes an NCO that only outputs cosines
 *  
 */
class NcoBundle[T <: Data](params: NcoParams[T]) extends Bundle {
  /** NCO accumulator input step size */
  val stepSize = Input(UInt(params.resolutionWidth.W))
  /** Output cosinde signal */ 
  val cos: T = Output(params.proto.cloneType)
  /** The NCO register output */ 
  val truncateRegOut = Output(UInt(params.truncateWidth.W))
//  val softRst = Input(Bool())

  override def cloneType: this.type = NcoBundle(params).asInstanceOf[this.type]
}

/** Factory for [[gps.NcoBundle]] instances. */
object NcoBundle {
  /** Creates an NcoBundle with given set of params.
   *
   *  @param params The NCO parameters 
   */
  def apply[T <: Data](params: NcoParams[T]): NcoBundle[T] = new NcoBundle(params)
}

/** NCO module 
 *
 *  An NCO, or numerically controlled oscillator, is a digital signal generator that creates a synchronous discrete sinusoid waveform. 
 *  This NCO an accumulator, look-up-table based design where 2^resolutionWidth represents a full period and the input step-size determines the frequency/period of the sinusoid. The accumulator register bit width is kept large for high fractional period resolution, and the output sinusoid value is binned/truncated to minimize fractional amplitude resolution.   
 *  
 *  Can calculate both sine and cosine outputs
 *
 *  @param params NCO parameters
 */
class NCO[T <: Data : Real](val params: NcoParams[T]) extends Module {
  /** NcoSineOutBundle IO */
  val io = IO(new NcoSinOutBundle(params)) 
    
  /** NCO base module instance */
  val cosNCO = Module(new NCOBase(params))  
    
  cosNCO.io.stepSize := io.stepSize
//  cosNCO.io.softRst := io.softRst
  io.cos := cosNCO.io.cos

  io.truncateRegOut := cosNCO.io.truncateRegOut
  io.sin := ConvertableTo[T].fromDouble(0.0)

  if (params.sinOut) {

    var coefficient = 1.0
    if (params.highRes) {
      coefficient = math.pow(2,max(params.truncateWidth-2,0))
    }
    else {
      coefficient = 1.0
    }
    /** LUT that contains sine values */
    val sineLUT = VecInit(NCOConstants.sine(params.truncateWidth).map((x:Double) => ConvertableTo[T].fromDouble(x*coefficient)))
    io.sin := sineLUT(cosNCO.io.truncateRegOut)
//    Mux(io.highRes,
//                  sineLUT(cosNCO.io.truncateRegOut) * ConvertableTo[T].fromDouble(8.0),
//                  sineLUT(cosNCO.io.truncateRegOut))
  }
}

/** NCO base module 
 * 
 *  Only calculates cosine outputs 
 *
 *  @param params NCO parameters
 */
class NCOBase[T <: Data : Real](val params: NcoParams[T]) extends Module {
  /** NcoBundle IO */
  val io = IO(new NcoBundle(params))
   
  /** Register that accumulates NCO value */
  val reg = RegInit(UInt(params.resolutionWidth.W), 0.U)
    
  var coefficient = 1.0
  if (params.highRes) {
    coefficient = math.pow(2,max(params.truncateWidth-2,0))
  }
  else {
    coefficient = 1.0
  }

  /** LUT that contains cosine values */
  val cosineLUT = VecInit(NCOConstants.cosine(params.truncateWidth).map((x:Double) => ConvertableTo[T].fromDouble(x*coefficient)))
  reg := reg + io.stepSize
  io.truncateRegOut := reg >> (params.resolutionWidth - params.truncateWidth)
  io.cos := cosineLUT(io.truncateRegOut)
}
