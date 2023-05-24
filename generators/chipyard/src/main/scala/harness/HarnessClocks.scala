package chipyard.harness

import chisel3._
import chisel3.util._
import chisel3.experimental.DoubleParam
import scala.collection.mutable.{ArrayBuffer, LinkedHashMap}
import freechips.rocketchip.diplomacy.{LazyModule}
import org.chipsalliance.cde.config.{Field, Parameters, Config}
import freechips.rocketchip.util.{ResetCatchAndSync}
import freechips.rocketchip.prci._

import chipyard.harness.{ApplyHarnessBinders, HarnessBinders, HarnessClockInstantiatorKey}
import chipyard.iobinders.HasIOBinders
import chipyard.clocking.{SimplePllConfiguration, ClockDividerN}


// HarnessClockInstantiators are classes which generate clocks that drive
// TestHarness simulation models and any Clock inputs to the ChipTop
trait HarnessClockInstantiator {
  val _clockMap: LinkedHashMap[String, (Double, ClockBundle)] = LinkedHashMap.empty

  // request a clock bundle at a particular frequency
  def requestClockBundle(name: String, freqRequested: Double): ClockBundle = {
    if (_clockMap.contains(name)) {
      require(freqRequested == _clockMap(name)._1,
        s"Request clock freq = $freqRequested != previously requested ${_clockMap(name)._2} for requested clock $name")
      _clockMap(name)._2
    } else {
      val clockBundle = Wire(new ClockBundle(ClockBundleParameters()))
      _clockMap(name) = (freqRequested, clockBundle)
      clockBundle
    }
  }

  // refClock is the clock generated by TestDriver that is
  // passed to the TestHarness as its implicit clock
  def instantiateHarnessClocks(refClock: ClockBundle): Unit
}

class ClockSourceAtFreqMHz(val freqMHz: Double) extends BlackBox(Map(
  "PERIOD" -> DoubleParam(1000/freqMHz)
)) with HasBlackBoxInline {
  val io = IO(new ClockSourceIO)
  val moduleName = this.getClass.getSimpleName

  setInline(s"$moduleName.v",
    s"""
      |module $moduleName #(parameter PERIOD="") (
      |    input power,
      |    input gate,
      |    output clk);
      |  timeunit 1ns/1ps;
      |  reg clk_i = 1'b0;
      |  always #(PERIOD/2.0) clk_i = ~clk_i & (power & ~gate);
      |  assign clk = clk_i;
      |endmodule
      |""".stripMargin)
}


// The AbsoluteFreqHarnessClockInstantiator uses a Verilog blackbox to
// provide the precise requested frequency.
// This ClockInstantiator cannot be synthesized, run in Verilator, or run in FireSim
// It is useful for VCS/Xcelium-driven RTL simulations
class AbsoluteFreqHarnessClockInstantiator extends HarnessClockInstantiator {
  def instantiateHarnessClocks(refClock: ClockBundle): Unit = {
    val sinks = _clockMap.map({ case (name, (freq, bundle)) =>
      ClockSinkParameters(take=Some(ClockParameters(freqMHz=freq / (1000 * 1000))), name=Some(name))
    }).toSeq

    // connect wires to clock source
    for (sinkParams <- sinks) {
      val source = Module(new ClockSourceAtFreqMHz(sinkParams.take.get.freqMHz))
      source.io.power := true.B
      source.io.gate := false.B

      _clockMap(sinkParams.name.get)._2.clock := source.io.clk
      _clockMap(sinkParams.name.get)._2.reset := refClock.reset
    }
  }
}

class WithAbsoluteFreqHarnessClockInstantiator extends Config((site, here, up) => {
  case HarnessClockInstantiatorKey => () => new AbsoluteFreqHarnessClockInstantiator
})

class AllClocksFromHarnessClockInstantiator extends HarnessClockInstantiator {
  def instantiateHarnessClocks(refClock: ClockBundle): Unit = {
    val freqs = _clockMap.map(_._2._1)
    freqs.tail.foreach(t => require(t == freqs.head, s"Mismatching clocks $t != ${freqs.head}"))
    for ((_, (_, bundle)) <- _clockMap) {
      bundle.clock := refClock.clock
      bundle.reset := refClock.reset
    }
  }
}

class WithAllClocksFromHarnessClockInstantiator extends Config((site, here, up) => {
  case HarnessClockInstantiatorKey => () => new AllClocksFromHarnessClockInstantiator
})