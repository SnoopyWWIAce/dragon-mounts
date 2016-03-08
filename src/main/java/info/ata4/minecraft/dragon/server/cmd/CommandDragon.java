/*
 ** 2012 August 24
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */
package info.ata4.minecraft.dragon.server.cmd;

import info.ata4.minecraft.dragon.server.entity.EntityTameableDragon;
import info.ata4.minecraft.dragon.server.entity.breeds.EnumDragonBreed;
import info.ata4.minecraft.dragon.server.entity.helper.EnumDragonLifeStage;
import net.minecraft.command.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.ArrayList;
import scala.actors.threadpool.Arrays;

/**
 *
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
public class CommandDragon extends CommandBase {
    
    private final List<String> commandNames;
    private final List<String> breedNames;
    private final List<String> lifeStageNames;
    
    public CommandDragon() {
        Command[] commands = Command.values();
        commandNames = new ArrayList<String>(commands.length);
        for (Command command : commands) {
            commandNames.add(command.name().toLowerCase());
        }
        
        List<EnumDragonBreed> breeds = Arrays.asList(EnumDragonBreed.values());
        breedNames = new ArrayList<String>(breeds.size());
        for (EnumDragonBreed breed : breeds) {
            breedNames.add(breed.getName());
        }
        
        EnumDragonLifeStage[] lifeStages = EnumDragonLifeStage.values();
        lifeStageNames = new ArrayList<String>(lifeStages.length);
        for (EnumDragonLifeStage lifeStage : lifeStages) {
            lifeStageNames.add(lifeStage.name().toLowerCase());
        }
    }

    @Override
    public String getCommandName() {
        return "dragon";
    }
    
    @Override
    public String getCommandUsage(ICommandSender sender) {
        String commands = StringUtils.join(commandNames, '|');
        return String.format("/dragon <%s> [global]", commands);
    }
    
    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, commandNames);
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("stage")) {
                return getListOfStringsMatchingLastWord(args, lifeStageNames);
            } else if (args[0].equalsIgnoreCase("breed")) {
                return getListOfStringsMatchingLastWord(args, breedNames);
            }
        }
        
        return null;
    }
    
    /**
     * Return the required permission level for this command.
     */
    @Override
    public int getRequiredPermissionLevel() {
        return 3;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] params) throws CommandException {
        if (params.length < 1 || params[0].isEmpty()) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        
        // last parameter, optional
        boolean global = params[params.length - 1].equalsIgnoreCase("global");

        Command cmd;
        
        try {
            cmd = Command.valueOf(params[0].toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        
        switch (cmd) {
            case STAGE: {
                if (params.length < 2) {
                    throw new WrongUsageException(getCommandUsage(sender));
                }

                EnumDragonLifeStage lifeStage = null;
                String parameter = params[1].toUpperCase();

                if (!parameter.equals("ITEM")) {
                    try {
                        lifeStage = EnumDragonLifeStage.valueOf(parameter);
                    } catch (IllegalArgumentException ex) {
                        throw new SyntaxErrorException();
                    }
                }

                EntityModifier modifier = new LifeStageModifier(lifeStage);
                applyModifier(sender, modifier, global);
                break;
            }
            
            case BREED: {
                if (params.length < 2) {
                    throw new WrongUsageException(getCommandUsage(sender));
                }

                String breedName = params[1].toLowerCase();
                EnumDragonBreed breed = EnumDragonBreed.fromName(breedName);

                if (breed == null) {
                    throw new SyntaxErrorException();
                }

                applyModifier(sender, new BreedModifier(breed), global);
                break;
            }
            
            case TAME: {
                if (sender instanceof EntityPlayerMP) {
                    EntityPlayerMP player = (EntityPlayerMP) sender;
                    applyModifier(sender, new TameModifier(player), global);
                } else {
                    // console can't tame dragons
                    throw new CommandException("commands.dragon.canttame");
                }
                break;
            }
        }
    }
    
    private void applyModifier(ICommandSender sender, EntityModifier modifier, boolean global) throws CommandException {
        if (!global && sender instanceof EntityPlayerMP) {
            EntityPlayerMP player = getCommandSenderAsPlayer(sender);
            double range = 64;
            AxisAlignedBB aabb = new AxisAlignedBB(
                    player.posX - 1, player.posY - 1, player.posZ - 1,
                    player.posX + 1, player.posY + 1, player.posZ + 1);
            aabb = aabb.expand(range, range, range);
            List<EntityTameableDragon> dragons = player.worldObj.getEntitiesWithinAABB(EntityTameableDragon.class, aabb);

            EntityTameableDragon closestDragon = null;
            float minPlayerDist = Float.MAX_VALUE;

            // get closest dragon
            for (EntityTameableDragon dragon : dragons) {
                float playerDist = dragon.getDistanceToEntity(player);
                if (dragon.getDistanceToEntity(player) < minPlayerDist) {
                    closestDragon = dragon;
                    minPlayerDist = playerDist;
                }
            }

            if (closestDragon == null) {
                throw new CommandException("commands.dragon.nodragons");
            } else {
                modifier.modify(closestDragon);
            }
        } else {
            // scan all entities on all dimensions
            MinecraftServer server = MinecraftServer.getServer();
            for (WorldServer worldServer : server.worldServers) {
                List<Entity> entities = worldServer.loadedEntityList;

                // note: don't use for-each here: ConcurrentModificationException!
                for (int i = 0; i < entities.size(); i++) {
                    Entity entity = entities.get(i);
                    if (!(entity instanceof EntityTameableDragon)) {
                        continue;
                    }

                    modifier.modify((EntityTameableDragon) entity);
                }
            }
        }
    }
    
    private enum Command {
        STAGE, BREED, TAME;
    }
}
