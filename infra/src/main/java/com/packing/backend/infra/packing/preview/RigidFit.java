package com.packing.backend.infra.packing.preview;

final class RigidFit {

    private static final int    JACOBI_SWEEPS  = 64;
    private static final double JACOBI_EPSILON = 1e-15;

    private final double[] sumAB = new double[9];

    private long   count;
    private double originAx;
    private double originAy;
    private double originAz;
    private double originBx;
    private double originBy;
    private double originBz;
    private double sumAx;
    private double sumAy;
    private double sumAz;
    private double sumBx;
    private double sumBy;
    private double sumBz;
    private double sumAA;
    private double sumBB;

    void add(double ax, double ay, double az, double bx, double by, double bz) {
        if (count == 0) {
            originAx = ax;
            originAy = ay;
            originAz = az;
            originBx = bx;
            originBy = by;
            originBz = bz;
        }
        count++;
        double x = ax - originAx;
        double y = ay - originAy;
        double z = az - originAz;
        double u = bx - originBx;
        double v = by - originBy;
        double w = bz - originBz;
        sumAx += x;
        sumAy += y;
        sumAz += z;
        sumBx += u;
        sumBy += v;
        sumBz += w;
        sumAA += x * x + y * y + z * z;
        sumBB += u * u + v * v + w * w;
        sumAB[0] += x * u;
        sumAB[1] += x * v;
        sumAB[2] += x * w;
        sumAB[3] += y * u;
        sumAB[4] += y * v;
        sumAB[5] += y * w;
        sumAB[6] += z * u;
        sumAB[7] += z * v;
        sumAB[8] += z * w;
    }

    long count() {
        return count;
    }

    Solution solve() {
        if (count == 0) {
            throw new IllegalStateException("A rigid fit needs at least one point pair");
        }
        double n = count;
        double meanAx = sumAx / n;
        double meanAy = sumAy / n;
        double meanAz = sumAz / n;
        double meanBx = sumBx / n;
        double meanBy = sumBy / n;
        double meanBz = sumBz / n;

        double sxx = sumAB[0] - n * meanAx * meanBx;
        double sxy = sumAB[1] - n * meanAx * meanBy;
        double sxz = sumAB[2] - n * meanAx * meanBz;
        double syx = sumAB[3] - n * meanAy * meanBx;
        double syy = sumAB[4] - n * meanAy * meanBy;
        double syz = sumAB[5] - n * meanAy * meanBz;
        double szx = sumAB[6] - n * meanAz * meanBx;
        double szy = sumAB[7] - n * meanAz * meanBy;
        double szz = sumAB[8] - n * meanAz * meanBz;

        double[][] matrix = {
                { sxx + syy + szz, syz - szy, szx - sxz, sxy - syx },
                { syz - szy, sxx - syy - szz, sxy + syx, szx + sxz },
                { szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy },
                { sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz } };
        double[][] vectors = identity();
        diagonalize(matrix, vectors);

        int best = 0;
        for (int i = 1; i < 4; i++) {
            if (matrix[i][i] > matrix[best][best]) {
                best = i;
            }
        }
        double qw = vectors[0][best];
        double qx = vectors[1][best];
        double qy = vectors[2][best];
        double qz = vectors[3][best];
        double norm = Math.sqrt(qw * qw + qx * qx + qy * qy + qz * qz);
        qw /= norm;
        qx /= norm;
        qy /= norm;
        qz /= norm;
        if (qw < 0) {
            qw = -qw;
            qx = -qx;
            qy = -qy;
            qz = -qz;
        }

        double spreadA = sumAA - n * (meanAx * meanAx + meanAy * meanAy + meanAz * meanAz);
        double spreadB = sumBB - n * (meanBx * meanBx + meanBy * meanBy + meanBz * meanBz);
        double residual = Math.max(0, spreadA + spreadB - 2 * matrix[best][best]);

        double centerAx = originAx + meanAx;
        double centerAy = originAy + meanAy;
        double centerAz = originAz + meanAz;
        double[] rotated = rotate(qx, qy, qz, qw, centerAx, centerAy, centerAz);
        return new Solution(qx,
                            qy,
                            qz,
                            qw,
                            originBx + meanBx - rotated[0],
                            originBy + meanBy - rotated[1],
                            originBz + meanBz - rotated[2],
                            Math.sqrt(residual / n));
    }

    static double[] rotate(double qx, double qy, double qz, double qw, double x, double y, double z) {
        double tx = 2 * (qy * z - qz * y);
        double ty = 2 * (qz * x - qx * z);
        double tz = 2 * (qx * y - qy * x);
        return new double[] {
                x + qw * tx + (qy * tz - qz * ty),
                y + qw * ty + (qz * tx - qx * tz),
                z + qw * tz + (qx * ty - qy * tx) };
    }

    private static double[][] identity() {
        double[][] identity = new double[4][4];
        for (int i = 0; i < 4; i++) {
            identity[i][i] = 1;
        }
        return identity;
    }

    private static void diagonalize(double[][] a, double[][] v) {
        for (int sweep = 0; sweep < JACOBI_SWEEPS; sweep++) {
            double off = 0;
            double scale = 0;
            for (int p = 0; p < 4; p++) {
                scale += Math.abs(a[p][p]);
                for (int q = p + 1; q < 4; q++) {
                    off += Math.abs(a[p][q]);
                }
            }
            if (off <= JACOBI_EPSILON * Math.max(scale, Double.MIN_NORMAL)) {
                return;
            }
            for (int p = 0; p < 4; p++) {
                for (int q = p + 1; q < 4; q++) {
                    if (a[p][q] != 0) {
                        rotateJacobi(a, v, p, q);
                    }
                }
            }
        }
    }

    private static void rotateJacobi(double[][] a, double[][] v, int p, int q) {
        double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
        double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
        if (theta == 0) {
            t = 1;
        }
        double c = 1 / Math.sqrt(t * t + 1);
        double s = t * c;
        for (int k = 0; k < 4; k++) {
            double akp = a[k][p];
            double akq = a[k][q];
            a[k][p] = c * akp - s * akq;
            a[k][q] = s * akp + c * akq;
        }
        for (int k = 0; k < 4; k++) {
            double apk = a[p][k];
            double aqk = a[q][k];
            a[p][k] = c * apk - s * aqk;
            a[q][k] = s * apk + c * aqk;
        }
        for (int k = 0; k < 4; k++) {
            double vkp = v[k][p];
            double vkq = v[k][q];
            v[k][p] = c * vkp - s * vkq;
            v[k][q] = s * vkp + c * vkq;
        }
    }

    record Solution(double qx, double qy, double qz, double qw, double tx, double ty, double tz, double rmsError) {
    }
}
