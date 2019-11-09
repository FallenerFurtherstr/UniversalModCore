package cam72cam.mod.entity;

import cam72cam.mod.entity.boundingbox.BoundingBox;
import cam72cam.mod.entity.custom.*;
import cam72cam.mod.item.ClickResult;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.net.Packet;
import cam72cam.mod.util.Hand;
import cam72cam.mod.util.TagCompound;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.registry.IEntityAdditionalSpawnData;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import java.util.*;
import java.util.stream.Collectors;

public class ModdedEntity extends Entity implements IEntityAdditionalSpawnData {
    private cam72cam.mod.entity.Entity self;

    private Map<UUID, Vec3d> passengerPositions = new HashMap<>();
    private List<SeatEntity> seats = new ArrayList<>();

    private EntitySettings settings;
    private String type;
    private IWorldData iWorldData;
    private ISpawnData iSpawnData;
    private ITickable iTickable;
    private IClickable iClickable;
    private IKillable iKillable;
    private IRidable iRidable;
    private ICollision iCollision;

    public ModdedEntity(World world) {
        super(world);

        super.preventEntitySpawning = true;
    }

    @Override
    protected final void entityInit() {
    }

    /* Init Self Wrapper */

    protected final void init(String type) {
        if (self == null) {
            this.type = type;
            self = EntityRegistry.create(type, this);

            EntitySettings settings = EntityRegistry.getSettings(type);
            super.isImmuneToFire = settings.immuneToFire;
            super.entityCollisionReduction = settings.entityCollisionReduction;
            this.settings = settings;

            iWorldData = IWorldData.get(self);
            iSpawnData = ISpawnData.get(self);
            iTickable = ITickable.get(self);
            iClickable = IClickable.get(self);
            iKillable = IKillable.get(self);
            iRidable = IRidable.get(self);
            iCollision = ICollision.get(self);
        }
    }

    private final void loadSelf(TagCompound data) {
        String type = data.getString("custom_mob_type");
        if (type == null) {
            // Legacy...
            type = data.getString("id");
        }
        if (type == null) {
            throw new RuntimeException("Invalid entity data: " + data);
        }
        init(type);
    }

    private final void saveSelf(TagCompound data) {
        data.setString("custom_mob_type", type);
    }

    public cam72cam.mod.entity.Entity getSelf() {
        return self;
    }

    /* IWorldData */

    @Override
    protected final void readEntityFromNBT(NBTTagCompound compound) {
        load(new TagCompound(compound));
    }

    private final void load(TagCompound data) {
        loadSelf(data);
        iWorldData.load(data);
        readPassengerData(data);
    }

    @Override
    protected final void writeEntityToNBT(NBTTagCompound compound) {
        save(new TagCompound(compound));
    }

    private final void save(TagCompound data) {
        iWorldData.save(data);
        saveSelf(data);
        writePassengerData(data);
    }

    /* ISpawnData */

    @Override
    public final void readSpawnData(ByteBuf additionalData) {
        TagCompound data = new TagCompound(ByteBufUtils.readTag(additionalData));
        this.entityUniqueID = data.getUUID("UUIDSYNC");
        loadSelf(data);
        iSpawnData.loadSpawn(data);
        self.sync.receive(data.get("sync"));
        readPassengerData(data);
    }

    @Override
    public final void writeSpawnData(ByteBuf buffer) {
        TagCompound data = new TagCompound();
        data.setUUID("UUIDSYNC", this.getPersistentID());
        iSpawnData.saveSpawn(data);
        saveSelf(data);
        data.set("sync", self.sync);
        writePassengerData(data);

        ByteBufUtils.writeTag(buffer, data.internal);
    }

    /* ITickable */

    @Override
    public void onEntityUpdate() {
        iTickable.onTick();
        self.sync.send();

        //TODO 1.7.10
        super.boundingBox.setBB(new BoundingBox(iCollision.getCollision()));

        if (this.riddenByEntity != null) {
            addPassenger(this.riddenByEntity);
        }

        if (!seats.isEmpty()) {
            seats.removeAll(seats.stream().filter(x -> x.isDead).collect(Collectors.toList()));
            seats.forEach(seat -> seat.setPosition(posX, posY, posZ));
        }
    }

    /* Player Interact */

    @Override
    public final boolean interactFirst(EntityPlayer player) {
        return iClickable.onClick(new Player(player), Hand.PRIMARY) == ClickResult.ACCEPTED;
    }

    /* Death */

    @Override
    public final boolean attackEntityFrom(DamageSource damagesource, float amount) {
        cam72cam.mod.entity.Entity wrapEnt = new cam72cam.mod.entity.Entity(damagesource.getSourceOfDamage());
        DamageType type;
        if (damagesource.isExplosion() && !(damagesource.getSourceOfDamage() instanceof EntityMob)) {
            type = DamageType.EXPLOSION;
        } else if (damagesource.getSourceOfDamage() instanceof EntityPlayer) {
            type = damagesource.isProjectile() ? DamageType.PROJECTILE : DamageType.PLAYER;
        } else {
            type = DamageType.OTHER;
        }
        iKillable.onDamage(type, wrapEnt, amount);

        return false;
    }

    @Override
    public final void setDead() {
        if (!this.isDead) {
            super.setDead();
            iKillable.onRemoved();
        }
    }

    /* Ridable */

    /* TODO 1.7.10
    @Override
    public boolean canFitPassenger(Entity passenger) {
        return iRidable.canFitPassenger(new cam72cam.mod.entity.Entity(passenger));
    }
    */

    private Vec3d calculatePassengerOffset(cam72cam.mod.entity.Entity passenger) {
        return passenger.getPosition().subtract(self.getPosition()).rotateMinecraftYaw(-self.getRotationYaw());
    }

    private Vec3d calculatePassengerPosition(Vec3d offset) {
        return offset.rotateMinecraftYaw(-self.getRotationYaw()).add(self.getPosition());
    }

    public final void addPassenger(Entity entity) {
        if (!worldObj.isRemote) {
            entity.mountEntity(null);
            System.out.println("New Seat");
            SeatEntity seat = new SeatEntity(worldObj);
            seat.setup(this, entity);
            cam72cam.mod.entity.Entity passenger = self.getWorld().getEntity(entity);
            passengerPositions.put(entity.getPersistentID(), iRidable.getMountOffset(passenger, calculatePassengerOffset(passenger)));
            seat.setPosition(posX, posY, posZ);
            entity.mountEntity(seat);
            updateSeat(seat);
            worldObj.spawnEntityInWorld(seat);
            self.sendToObserving(new PassengerPositionsPacket(this));
        } else {
            System.out.println("skip");
        }
    }

    List<cam72cam.mod.entity.Entity> getActualPassengers() {
        return seats.stream()
                .map(SeatEntity::getEntityPassenger)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    void updateSeat(SeatEntity seat) {
        if (!seats.contains(seat)) {
            seats.add(seat);
        }

        cam72cam.mod.entity.Entity passenger = seat.getEntityPassenger();
        if (passenger != null) {
            Vec3d offset = passengerPositions.get(passenger.getUUID());
            // Weird case around player joining with a different UUID during debugging
            if (offset == null) {
                offset = iRidable.getMountOffset(passenger, calculatePassengerOffset(passenger));
                passengerPositions.put(passenger.getUUID(), offset);
            }

            offset = iRidable.onPassengerUpdate(passenger, offset);
            if (seat.riddenByEntity != passenger.internal) {
                return;
            }

            passengerPositions.put(passenger.getUUID(), offset);

            Vec3d pos = calculatePassengerPosition(offset);
            pos = pos.add(0, 1.5, 0);

            if (worldObj.loadedEntityList.indexOf(seat) < worldObj.loadedEntityList.indexOf(passenger.internal)) {
                pos = pos.add(motionX, motionY, motionZ);
            }

            passenger.setPosition(pos);
            passenger.setVelocity(new Vec3d(motionX, motionY, motionZ));

            float delta = rotationYaw - prevRotationYaw;
            passenger.internal.rotationYaw = passenger.internal.rotationYaw + delta;

            seat.shouldSit = iRidable.shouldRiderSit(passenger);
        }
    }

    boolean isPassenger(cam72cam.mod.entity.Entity passenger) {
        return getActualPassengers().stream().anyMatch(p -> p.getUUID().equals(passenger.getUUID()));
    }

    void removeSeat(SeatEntity seat) {
        cam72cam.mod.entity.Entity passenger = seat.getEntityPassenger();
        if (passenger != null) {
            Vec3d offset = passengerPositions.get(passenger.getUUID());
            if (offset != null) {
                offset = iRidable.onDismountPassenger(passenger, offset);
                passenger.setPosition(calculatePassengerPosition(offset));
            }
            passengerPositions.remove(passenger.getUUID());
        }
        seats.remove(seat);
    }

    void removePassenger(cam72cam.mod.entity.Entity passenger) {
        for (SeatEntity seat : this.seats) {
            cam72cam.mod.entity.Entity seatPass = seat.getEntityPassenger();
            if (seatPass != null && seatPass.getUUID().equals(passenger.getUUID())) {
                passenger.internal.mountEntity(null);
                break;
            }
        }
    }

    @Override
    public boolean canRiderInteract() {
        return false;
    }

    public int getPassengerCount() {
        return seats.size();
    }

    private void readPassengerData(TagCompound data) {
        passengerPositions = data.getMap("passengers", UUID::fromString, (TagCompound tag) -> tag.getVec3d("pos"));
    }

    private void writePassengerData(TagCompound data) {
        data.setMap("passengers", passengerPositions, UUID::toString, (Vec3d pos) -> {
            TagCompound tmp = new TagCompound();
            tmp.setVec3d("pos", pos);
            return tmp;
        });
    }

    /* ICollision */
    @Override
    public AxisAlignedBB getCollisionBox(Entity collider) {
        return null;
    }

    @Override
    public AxisAlignedBB getBoundingBox() {
        return new BoundingBox(iCollision.getCollision());
    }

    /* TODO 1.7.10
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        AxisAlignedBB bb = this.getEntityBoundingBox();
        return new AxisAlignedBB(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
    }
    */

    /* Hacks */
    @Override
    public boolean canBeCollidedWith() {
        // Needed for right click, probably a forge or MC bug
        return true;
    }

    @Override
    public boolean canBePushed() {
        return settings.canBePushed;
    }

    @SideOnly(Side.CLIENT)
    public void setPositionAndRotation2(double x, double y, double z, float yaw, float pitch, int posRotationIncrements) {
        if (settings.defaultMovement) {
            super.setPositionAndRotation2(x, y, z, yaw, pitch, posRotationIncrements);
        }
    }

    @Override
    public void setVelocity(double x, double y, double z) {
        if (settings.defaultMovement) {
            super.setVelocity(x, y, z);
        }
    }

    /*
     * Disable standard entity sync
     */

    public static class PassengerPositionsPacket extends Packet {
        public PassengerPositionsPacket() {
            // Forge Reflection
        }

        public PassengerPositionsPacket(ModdedEntity stock) {
            data.setEntity("stock", stock.self);

            stock.writePassengerData(data);
        }

        @Override
        public void handle() {
            cam72cam.mod.entity.Entity entity = data.getEntity("stock", getWorld());
            if (entity != null && entity.internal instanceof ModdedEntity) {
                ModdedEntity stock = (ModdedEntity) entity.internal;
                stock.readPassengerData(data);
            }
        }
    }

    public String getName() {
        return this.type;
    }

    /*
     * TODO!!!
     */
    /*
    //@Override
    public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return true;
        }
        return false;//super.hasCapability(energyCapability, facing);
    }

    @SuppressWarnings("unchecked")
	//@Override
    public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return (T) cargoItems;
        }
        return null;//super.getCapability(energyCapability, facing);
    }

	@Override
    public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return true;
        }
        return super.hasCapability(capability, facing);
    }

    @SuppressWarnings("unchecked")
	@Override
    public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return (T) theTank;
        }
        return super.getCapability(capability, facing);
    }
     */
}
