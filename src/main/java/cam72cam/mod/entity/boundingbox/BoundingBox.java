package cam72cam.mod.entity.boundingbox;

import cam72cam.mod.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Optional;

public class BoundingBox extends Box {
    public final IBoundingBox internal;

    private BoundingBox(IBoundingBox internal, double[] constructorParams) {
        super(constructorParams[0], constructorParams[1], constructorParams[2], constructorParams[3], constructorParams[4], constructorParams[5]);
        this.internal = internal;
    }

    private BoundingBox(IBoundingBox internal) {
        this(internal, hack(internal));
    }

    public static Box from(IBoundingBox internal) {
        if (internal instanceof DefaultBoundingBox) {
            return ((DefaultBoundingBox) internal).internal;
        }
        return new BoundingBox(internal);
    }

    private static double[] hack(IBoundingBox internal) {
        Vec3d min = internal.min();
        Vec3d max = internal.max();
        return new double[]{max.x, max.y, max.z, min.x, min.y, min.z};
    }

    @Override
    public Box intersection(Box box_1) {
        return this;
    }

    @Override
    public BoundingBox union(Box other) {
        // Used by piston
        // Used by entityliving for BB stuff
        return this;
    }

    /* Modifiers */

    @Override
    public BoundingBox expand(double x, double y, double z) {
        return new BoundingBox(internal.grow(new Vec3d(x, y, z)));
    }

    @Override
    public BoundingBox stretch(double x, double y, double z) {
        return new BoundingBox(internal.expand(new Vec3d(x, y, z)));
    }
    @Override
    public BoundingBox shrink(double x, double y, double z) {
        return new BoundingBox(internal.contract(new Vec3d(x, y, z)));
    }


    @Override
    public BoundingBox offset(double x, double y, double z) {
        return new BoundingBox(internal.offset(new Vec3d(x, y, z)));
    }

    @Override
    public Box offset(BlockPos pos) {
        return offset(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public boolean intersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return super.intersects(minX, minY, minZ, maxX, maxY, maxZ) && // Fast check
                internal.intersects(new Vec3d(minX, minY, minZ), new Vec3d(maxX, maxY, maxZ)); // Slow check
    }

    @Override
    public boolean contains(net.minecraft.util.math.Vec3d vec) {
        return internal.contains(new Vec3d(vec));
    }

    @Override
    public Optional<net.minecraft.util.math.Vec3d> rayTrace(net.minecraft.util.math.Vec3d vecA, net.minecraft.util.math.Vec3d vecB) {
        int steps = 10;
        double xDist = vecB.x - vecA.x;
        double yDist = vecB.y - vecA.y;
        double zDist = vecB.z - vecA.z;
        double xDelta = xDist / steps;
        double yDelta = yDist / steps;
        double zDelta = zDist / steps;
        for (int step = 0; step < steps; step++) {
            Vec3d stepPos = new Vec3d(vecA.x + xDelta * step, vecA.y + yDelta * step, vecA.z + zDelta * step);
            if (internal.contains(stepPos)) {
                return Optional.of(stepPos.internal());
            }
        }
        return Optional.empty();
    }
}
