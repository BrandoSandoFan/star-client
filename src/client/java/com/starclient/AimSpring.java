package com.starclient;

/**
 * Critically damped spring on one rotation axis, advanced one tick at a time with the closed-form solution
 * (no overshoot, no randomness: the same inputs always give the same turn). Position is the remaining angle
 * between the view and the target; the spring pulls it to zero.
 */
public final class AimSpring {
	private double velocity;

	public void reset() {
		velocity = 0;
	}

	/**
	 * Advances one tick.
	 *
	 * @param error angle still to turn, in degrees (target minus current)
	 * @param omega stiffness per tick; the error decays roughly as (1 + omega*t) * e^(-omega*t)
	 * @param cap   largest turn allowed this tick, in degrees; the spring's velocity is scaled by the same ratio so it stays consistent
	 * @return degrees to add to the current angle
	 */
	public float step(float error, double omega, float cap) {
		double x = -error;
		double decay = Math.exp(-omega);
		double b = velocity + omega * x;
		double nextX = (x + b) * decay;
		double nextVelocity = (velocity - omega * b) * decay;
		double delta = nextX - x;
		double limit = Math.abs(cap);
		if (Math.abs(delta) > limit) {
			double scale = limit / Math.abs(delta);
			delta *= scale;
			nextVelocity *= scale;
		}
		velocity = nextVelocity;
		return (float) delta;
	}
}
