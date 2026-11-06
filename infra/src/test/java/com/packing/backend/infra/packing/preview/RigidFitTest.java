package com.packing.backend.infra.packing.preview;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RigidFitTest {

    private static final double[][] POINTS = randomPoints(500, 7);

    @Test
    void recoversAnArbitraryRotationAndTranslation() {
        double[] q = normalize(0.3, -0.5, 0.7, 0.4);

        RigidFit.Solution solution = fit(q, 12.5, -3.25, 100);

        assertRotation(solution, q);
        assertThat(solution.tx()).isCloseTo(12.5, within(1e-9));
        assertThat(solution.ty()).isCloseTo(-3.25, within(1e-9));
        assertThat(solution.tz()).isCloseTo(100, within(1e-9));
        assertThat(solution.rmsError()).isLessThan(1e-5);
    }

    @Test
    void recoversTheIdentity() {
        RigidFit.Solution solution = fit(new double[] { 0, 0, 0, 1 }, 0, 0, 0);

        assertRotation(solution, new double[] { 0, 0, 0, 1 });
        assertThat(solution.rmsError()).isLessThan(1e-5);
    }

    @Test
    void recoversHalfTurnsWhoseScalarPartIsZero() {
        double[] halfTurnAboutZ = { 0, 0, 1, 0 };

        RigidFit.Solution solution = fit(halfTurnAboutZ, 1, 2, 3);

        assertRotation(solution, halfTurnAboutZ);
        assertThat(solution.rmsError()).isLessThan(1e-5);
    }

    @Test
    void reportsRoundingNoiseAsASmallResidual() {
        double[] q = normalize(1, 2, 3, 4);
        Random random = new Random(11);
        RigidFit fit = new RigidFit();
        for (double[] p : POINTS) {
            double[] b = RigidFit.rotate(q[0], q[1], q[2], q[3], p[0], p[1], p[2]);
            fit.add(p[0], p[1], p[2], b[0] + 5 + noise(random), b[1] + noise(random), b[2] + noise(random));
        }

        RigidFit.Solution solution = fit.solve();

        assertThat(solution.rmsError()).isBetween(1e-5, 1e-3);
    }

    @Test
    void cannotExplainAMirrorImageWithARotation() {
        RigidFit fit = new RigidFit();
        for (double[] p : POINTS) {
            fit.add(p[0], p[1], p[2], -p[0], p[1], p[2]);
        }

        assertThat(fit.solve()
                      .rmsError()).isGreaterThan(1);
    }

    @Test
    void cannotExplainAScaledCopyWithARotation() {
        RigidFit fit = new RigidFit();
        for (double[] p : POINTS) {
            fit.add(p[0], p[1], p[2], 1.1 * p[0], 1.1 * p[1], 1.1 * p[2]);
        }

        assertThat(fit.solve()
                      .rmsError()).isGreaterThan(0.1);
    }

    private static RigidFit.Solution fit(double[] q, double tx, double ty, double tz) {
        RigidFit fit = new RigidFit();
        for (double[] p : POINTS) {
            double[] b = RigidFit.rotate(q[0], q[1], q[2], q[3], p[0], p[1], p[2]);
            fit.add(p[0], p[1], p[2], b[0] + tx, b[1] + ty, b[2] + tz);
        }
        assertThat(fit.count()).isEqualTo(POINTS.length);
        return fit.solve();
    }

    private static void assertRotation(RigidFit.Solution solution, double[] expected) {
        double dot = solution.qx() * expected[0] + solution.qy() * expected[1] + solution.qz() * expected[2] + solution.qw() * expected[3];
        assertThat(Math.abs(dot)).isCloseTo(1, within(1e-9));
        assertThat(solution.qw()).isGreaterThanOrEqualTo(0);
    }

    private static double[] normalize(double x, double y, double z, double w) {
        double norm = Math.sqrt(x * x + y * y + z * z + w * w);
        return new double[] { x / norm, y / norm, z / norm, w / norm };
    }

    private static double noise(Random random) {
        return (random.nextDouble() - 0.5) * 1e-3;
    }

    private static double[][] randomPoints(int count, long seed) {
        Random random = new Random(seed);
        double[][] points = new double[count][];
        for (int i = 0; i < count; i++) {
            points[i] = new double[] { random.nextDouble() * 40 - 20, random.nextDouble() * 10, random.nextDouble() * 4 + 50 };
        }
        return points;
    }
}
