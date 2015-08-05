package info.ata4.minecraft.dragon.server.entity.helper.breath;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.*;
import net.minecraft.world.World;

import java.util.*;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkElementIndex;
import static com.google.common.base.Preconditions.checkNotNull;

/**
* Created by TGG on 30/07/2015.
* BreathAffectedArea base class
- generated by BreedHelper
- stores the area of effect; called every tick with the breathing direction; applies the effects of the breath weapon
- derived classes for each type of breath
Ctor
- update (not breathing) or update(start, finish)
- affectBlock for each block
- affectEntity for each entity
*/
public class BreathAffectedArea
{
  public BreathAffectedArea(BreathWeapon i_breathWeapon)
  {
    breathWeapon = i_breathWeapon;
  }

  /**
   * Tell BreathAffectedArea that breathing is ongoing.  Call once per tick before updateTick()
   * @param world
   * @param origin  the origin of the beam
   * @param destination the destination of the beam, used to calculate direction
   * @param power
   */
  public void continueBreathing(World world, Vec3 origin, Vec3 destination, BreathNode.Power power)
  {
    Vec3 direction = destination.subtract(origin).normalize();

    EntityBreathNode newNode = EntityBreathNode.createEntityBreathNodeServer(
            world, origin.xCoord, origin.yCoord, origin.zCoord, direction.xCoord, direction.yCoord, direction.zCoord,
            power);

    entityBreathNodes.add(newNode);
  }

  /** updates the BreathAffectedArea, called once per tick
   */
  public void updateTick(World world) {
    ArrayList<NodeLineSegment> segments = new ArrayList<NodeLineSegment>();

    // create a list of NodeLineSegments from the motion path of the BreathNodes
    Iterator<EntityBreathNode> it = entityBreathNodes.iterator();
    while (it.hasNext()) {
      EntityBreathNode entity = it.next();
      if (entity.isDead) {
        it.remove();
      } else {
        float radius = entity.getCurrentRadius();
        Vec3 initialPosition = entity.getPositionVector();
        entity.onUpdate();
        Vec3 finalPosition = entity.getPositionVector();
        segments.add(new NodeLineSegment(initialPosition, finalPosition, radius));
      }
    }

    updateBlockAndEntityHitDensities(world, segments, entityBreathNodes, blocksAffectedByBeam, entitiesAffectedByBeam);

    implementEffectsOnBlocksTick(world, blocksAffectedByBeam);
    implementEffectsOnEntitiesTick(world, entitiesAffectedByBeam);

    decayBlockAndEntityHitDensities(blocksAffectedByBeam, entitiesAffectedByBeam);
  }

  private void implementEffectsOnBlocksTick(World world, HashMap<Vec3i, Float> affectedBlocks )
  {
    for (Map.Entry<Vec3i, Float> blockInfo : affectedBlocks.entrySet()) {
      float newHitDensity = breathWeapon.affectBlock(world, blockInfo.getKey(), blockInfo.getValue());
      blockInfo.setValue(newHitDensity);
    }
  }

  private void implementEffectsOnEntitiesTick(World world, HashMap<Integer, Float> affectedEntities )
  {
    for (Map.Entry<Vec3i, Float> blockInfo : affectedEntities.entrySet()) {
      float newHitDensity = breathWeapon.affectEntity(world, blockInfo.getKey(), blockInfo.getValue());
      blockInfo.setValue(newHitDensity);
    }
  }

  /**
   * decay the hit densities of the affected blocks and entities (eg for flame weapon - cools down)
   */
  private void decayBlockAndEntityHitDensities(HashMap<Vec3i, Float> affectedBlocks, HashMap<Integer, Float> affectedEntities)
  {
    Iterator<Map.Entry<Vec3i, Float>> itAffectedBlocks = affectedBlocks.entrySet().iterator();
    while (itAffectedBlocks.hasNext()) {
      Map.Entry<Vec3i, Float> affectedBlock = itAffectedBlocks.next();
      float carryover = affectedBlock.getValue();
      carryover = breathWeapon.decayBlockEffectTick(carryover);
      if (carryover >= 0.0F) {
        affectedBlock.setValue(carryover);
      } else {
        itAffectedBlocks.remove();
      }
    }

    Iterator<Map.Entry<Integer, Float>> itAffectedEntities = affectedEntities.entrySet().iterator();
    while (itAffectedBlocks.hasNext()) {
      Map.Entry<Integer, Float> affectedEntity = itAffectedEntities.next();
      float carryover = affectedEntity.getValue();
      carryover = breathWeapon.decayEntityEffectTick(carryover);
      if (carryover >= 0.0F) {
        affectedEntity.setValue(carryover);
      } else {
        itAffectedEntities.remove();
      }
    }
  }

  /**
   * Models the collision of the breath nodes on the world blocks and entities:
   * Each breathnode which contacts a world block will increase the corresponding 'hit density' by an amount proportional
   *   to the intensity of the node and the degree of overlap between the node and the block.
   * Likewise for the entities contacted by the breathnode
   * @param world
   * @param nodeLineSegments the nodeLineSegments in the breath weapon beam
   * @param entityBreathNodes the breathnodes in the breath weapon beam  - parallel to nodeLineSegments, must correspond 1:1
   * @param affectedBlocks each block touched by the beam has an entry in this map.  The hitDensity (float) is increased
   *                       every time a node touches it.  blocks without an entry haven't been touched.
   * @param affectedEntities every entity touched by the beam has an entry in this map (entityID).  The hitDensity (float)
   *                         for an entity is increased every time a node touches it.  entities without an entry haven't
   *                         been touched.
   */
  private void updateBlockAndEntityHitDensities(World world,
                                                ArrayList<NodeLineSegment> nodeLineSegments, ArrayList<EntityBreathNode> entityBreathNodes,
                                                HashMap<Vec3i, Float> affectedBlocks, HashMap<Integer, Float> affectedEntities)
  {
    checkNotNull(nodeLineSegments);
    checkNotNull(entityBreathNodes);
    checkNotNull(affectedBlocks);
    checkNotNull(affectedEntities);
    checkArgument(nodeLineSegments.size() == entityBreathNodes.size());

    if (entityBreathNodes.isEmpty()) return;

    final int NUMBER_OF_CLOUD_POINTS = 10;
    for (int i = 0; i < nodeLineSegments.size(); ++i) {
      float intensity = entityBreathNodes.get(i).getCurrentIntensity();
      nodeLineSegments.get(i).addStochasticCloud(affectedBlocks, intensity, NUMBER_OF_CLOUD_POINTS);
    }

    AxisAlignedBB allAABB = NodeLineSegment.getAxisAlignedBoundingBoxForAll(nodeLineSegments);
    List<EntityLivingBase> allEntities = world.getEntitiesWithinAABB(EntityLivingBase.class, allAABB);

    Multimap<Vec3i, Integer> occupiedByEntities = ArrayListMultimap.create();
    Map<Integer, AxisAlignedBB> entityHitBoxes = new HashMap<Integer, AxisAlignedBB>();
    for (EntityLivingBase entityLivingBase : allEntities) {
      AxisAlignedBB aabb = entityLivingBase.getEntityBoundingBox();
      entityHitBoxes.put(entityLivingBase.getEntityId(), aabb);
      for (int x = (int)aabb.minX; x <= (int)aabb.maxX; ++x) {
        for (int y = (int)aabb.minY; y <= (int)aabb.maxY; ++y) {
          for (int z = (int)aabb.minZ; z <= (int)aabb.maxZ; ++z) {
            Vec3i pos = new Vec3i(x, y, z);
            occupiedByEntities.put(pos, entityLivingBase.getEntityId());
          }
        }
      }
    }

    final int NUMBER_OF_ENTITY_CLOUD_POINTS = 10;
    for (int i = 0; i < nodeLineSegments.size(); ++i) {
      Set<Integer> checkedEntities = new HashSet<Integer>();
      AxisAlignedBB aabb = nodeLineSegments.get(i).getAxisAlignedBoundingBox();
      for (int x = (int)aabb.minX; x <= (int)aabb.maxX; ++x) {
        for (int y = (int)aabb.minY; y <= (int)aabb.maxY; ++y) {
          for (int z = (int)aabb.minZ; z <= (int)aabb.maxZ; ++z) {
            Vec3i pos = new Vec3i(x, y, z);
            Collection<Integer> entitiesHere = occupiedByEntities.get(pos);
            if (entitiesHere != null) {
              for (Integer entityID : entitiesHere) {
                if (!checkedEntities.contains(entityID)) {
                  checkedEntities.add(entityID);
                  float intensity = entityBreathNodes.get(i).getCurrentIntensity();
                  float hitDensity = nodeLineSegments.get(i).collisionCheckAABB(aabb, intensity, NUMBER_OF_ENTITY_CLOUD_POINTS);
                  if (hitDensity > 0.0) {
                    Float currentDensity = affectedEntities.get(entityID);
                    if (currentDensity == null) {
                      currentDensity = 0.0F;
                    }
                    currentDensity += hitDensity;
                    affectedEntities.put(entityID, currentDensity);
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private ArrayList<EntityBreathNode> entityBreathNodes = new ArrayList<EntityBreathNode>();
  private HashMap<Vec3i, Float> blocksAffectedByBeam = new HashMap<Vec3i, Float>();
  private HashMap<Integer, Float> entitiesAffectedByBeam = new HashMap<Integer, Float>();

  private BreathWeapon breathWeapon;

}
