package de.erdbeerbaerlp.dcintegration.forge;

import dcshadow.net.kyori.adventure.text.Component;
import dcshadow.net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import de.erdbeerbaerlp.dcintegration.common.Discord;
import de.erdbeerbaerlp.dcintegration.common.addon.AddonLoader;
import de.erdbeerbaerlp.dcintegration.common.addon.DiscordAddonMeta;
import de.erdbeerbaerlp.dcintegration.common.compat.DynmapListener;
import de.erdbeerbaerlp.dcintegration.common.storage.CommandRegistry;
import de.erdbeerbaerlp.dcintegration.common.storage.Configuration;
import de.erdbeerbaerlp.dcintegration.common.storage.PlayerLinkController;
import de.erdbeerbaerlp.dcintegration.common.util.DiscordMessage;
import de.erdbeerbaerlp.dcintegration.common.util.MessageUtils;
import de.erdbeerbaerlp.dcintegration.common.util.UpdateChecker;
import de.erdbeerbaerlp.dcintegration.common.util.Variables;
import de.erdbeerbaerlp.dcintegration.forge.api.ForgeDiscordEventHandler;
import de.erdbeerbaerlp.dcintegration.forge.bstats.Metrics;
import de.erdbeerbaerlp.dcintegration.forge.command.McCommandDiscord;
import de.erdbeerbaerlp.dcintegration.forge.util.ForgeMessageUtils;
import de.erdbeerbaerlp.dcintegration.forge.util.ForgeServerInterface;
import net.dv8tion.jda.api.entities.*;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fmllegacy.network.FMLNetworkConstants;
import net.minecraftforge.fmlserverevents.FMLServerStartedEvent;
import net.minecraftforge.fmlserverevents.FMLServerStoppedEvent;
import net.minecraftforge.fmlserverevents.FMLServerStoppingEvent;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static de.erdbeerbaerlp.dcintegration.common.util.Variables.discordDataDir;
import static de.erdbeerbaerlp.dcintegration.common.util.Variables.discord_instance;

@Mod(DiscordIntegration.MODID)
public class DiscordIntegration {
    /**
     * Modid
     */
    public static final String MODID = "dcintegration";
    /**
     * Contains timed-out player UUIDs, gets filled in MixinNetHandlerPlayServer
     */
    public static final ArrayList<UUID> timeouts = new ArrayList<>();
    private boolean stopped = false;

    public DiscordIntegration() {
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class, () -> new IExtensionPoint.DisplayTest(() -> FMLNetworkConstants.IGNORESERVERONLY, (a, b) -> true));
try {
            Configuration.instance().loadConfig();
            if (FMLEnvironment.dist == Dist.CLIENT) {
                System.err.println("This mod cannot be used clientside");
            } else if (!Configuration.instance().general.botToken.equals("INSERT BOT TOKEN HERE")) { //Prevent events when token not set or on client
                FMLJavaModLoadingContext.get().getModEventBus().addListener(this::serverSetup);
                MinecraftForge.EVENT_BUS.register(this);

        } else {
            System.err.println("Please check the config file and set an bot token");}
        } catch (IOException e) {
            System.err.println("Config loading failed");
            e.printStackTrace();
        } catch (IllegalStateException e) {
            System.err.println("Failed to read config file! Please check your config file!\nError description: " + e.getMessage());
            System.err.println("\nStacktrace: ");
            e.printStackTrace();
        }


        //  ==  Migrate some files from 1.x.x to 2.x.x  ==

        //LinkedPlayers JSON file
        final File linkedOld = new File("./linkedPlayers.json");
        final File linkedNew = new File(discordDataDir, "LinkedPlayers.json");

        //Player Ignores
        final File ignoreOld = new File("./players_ignoring_discord_v2");
        final File ignoreNew = new File(discordDataDir, ".PlayerIgnores");

        //Create data directory if missing
        if (!discordDataDir.exists()) discordDataDir.mkdir();

        //Move Files
        if (linkedOld.exists() && !linkedNew.exists()) {
            linkedOld.renameTo(linkedNew);
        }
        if (ignoreOld.exists() && !ignoreNew.exists()) {
            ignoreOld.renameTo(ignoreNew);
        }
    }


    public void serverSetup(FMLDedicatedServerSetupEvent ev) {
        Variables.discord_instance = new Discord(new ForgeServerInterface());
        try {
            //Wait a short time to allow JDA to get initiaized
            System.out.println("Waiting for JDA to initialize to send starting message... (max 5 seconds before skipping)");
            for (int i = 0; i <= 5; i++) {
                if (discord_instance.getJDA() == null) Thread.sleep(1000);
                else break;
            }
            if (discord_instance.getJDA() != null) {
                Thread.sleep(2000); //Wait for it to cache the channels
                if (!Configuration.instance().localization.serverStarting.isEmpty()) {
                    CommandRegistry.registerDefaultCommandsFromConfig();
                    if (discord_instance.getChannel() != null)
                        Variables.startingMsg = discord_instance.sendMessageReturns(Configuration.instance().localization.serverStarting, discord_instance.getChannel(Configuration.instance().advanced.serverChannelID));
                }
            }
        } catch (InterruptedException | NullPointerException ignored) {
        }
    }

    @SubscribeEvent
    public void playerJoin(final PlayerEvent.PlayerLoggedInEvent ev) {
        if (PlayerLinkController.getSettings(null, ev.getPlayer().getUUID()).hideFromDiscord) return;
        if (discord_instance != null) {
            discord_instance.sendMessage(Configuration.instance().localization.playerJoin.replace("%player%", ForgeMessageUtils.formatPlayerName(ev.getPlayer())));

            // Fix link status (if user does not have role, give the role to the user, or vice versa)
            final Thread fixLinkStatus = new Thread(() -> {
                if (Configuration.instance().linking.linkedRoleID.equals("0")) return;
                final UUID uuid = ev.getPlayer().getUUID();
                if (!PlayerLinkController.isPlayerLinked(uuid)) return;
                final Guild guild = discord_instance.getChannel().getGuild();
                final Role linkedRole = guild.getRoleById(Configuration.instance().linking.linkedRoleID);
                if (PlayerLinkController.isPlayerLinked(uuid)) {
                    final Member member = guild.getMemberById(PlayerLinkController.getDiscordFromPlayer(uuid));
                    if (!member.getRoles().contains(linkedRole))
                        guild.addRoleToMember(member, linkedRole).queue();
                }
            });
            fixLinkStatus.setDaemon(true);
            fixLinkStatus.start();
        }
    }

    @SubscribeEvent
    public void advancement(AdvancementEvent ev) {
        if (PlayerLinkController.getSettings(null, ev.getPlayer().getUUID()).hideFromDiscord) return;
        if (ev.getPlayer().getServer().getPlayerList().getPlayerAdvancements((ServerPlayer) ev.getPlayer()).getOrStartProgress(ev.getAdvancement()).isDone())
            if (discord_instance != null && ev.getAdvancement() != null && ev.getAdvancement().getDisplay() != null && ev.getAdvancement().getDisplay().shouldAnnounceChat())
                discord_instance.sendMessage(Configuration.instance().localization.advancementMessage.replace("%player%",
                                ChatFormatting.stripFormatting(ForgeMessageUtils.formatPlayerName(ev.getPlayer())))
                        .replace("%name%",
                                ChatFormatting.stripFormatting(ev.getAdvancement()
                                        .getDisplay()
                                        .getTitle()
                                        .getString()))
                        .replace("%desc%",
                                ChatFormatting.stripFormatting(ev.getAdvancement()
                                        .getDisplay()
                                        .getDescription()
                                        .getString()))
                        .replace("\\n", "\n"));


    }

    @SubscribeEvent
    public void registerCommands(final RegisterCommandsEvent ev) {
        new McCommandDiscord(ev.getDispatcher());
    }

    @SubscribeEvent
    public void serverStarted(final FMLServerStartedEvent ev) {
        System.out.println("Started");
        Variables.started = new Date().getTime();
        if (discord_instance != null) {
            if (Variables.startingMsg != null) {
                Variables.startingMsg.thenAccept((a) -> a.editMessage(Configuration.instance().localization.serverStarted).queue());
            } else discord_instance.sendMessage(Configuration.instance().localization.serverStarted);
        }
        if (discord_instance != null) {
            discord_instance.startThreads();
        }
        UpdateChecker.runUpdateCheck("https://raw.githubusercontent.com/ErdbeerbaerLP/Discord-Chat-Integration/1.17/update_checker.json");
        if (ModList.get().getModContainerById("dynmap").isPresent()) {
            new DynmapListener().register();
        }
        final Metrics bstats = new Metrics(ModList.get().getModContainerById(MODID).get(), 9765);
        bstats.addCustomChart(new Metrics.SimplePie("webhook_mode", () -> Configuration.instance().webhook.enable ? "Enabled" : "Disabled"));
        bstats.addCustomChart(new Metrics.SimplePie("command_log", () -> !Configuration.instance().commandLog.channelID.equals("0") ? "Enabled" : "Disabled"));
        bstats.addCustomChart(new Metrics.DrilldownPie("addons", () -> {
            final Map<String, Map<String, Integer>> map = new HashMap<>();
            if (Configuration.instance().bstats.sendAddonStats) {  //Only send if enabled, else send empty map
                for (DiscordAddonMeta m : AddonLoader.getAddonMetas()) {
                    final Map<String, Integer> entry = new HashMap<>();
                    entry.put(m.getVersion(), 1);
                    map.put(m.getName(), entry);
                }
            }
            return map;
        }));
    }

    @SubscribeEvent
    public void command(CommandEvent ev) {
        String command = ev.getParseResults().getReader().getString().replaceFirst(Pattern.quote("/"), "");
        if (!Configuration.instance().commandLog.channelID.equals("0")) {
            if (!ArrayUtils.contains(Configuration.instance().commandLog.ignoredCommands, command.split(" ")[0]))
                discord_instance.sendMessage(Configuration.instance().commandLog.message
                        .replace("%sender%", ev.getParseResults().getContext().getLastChild().getSource().getTextName())
                        .replace("%cmd%", command)
                        .replace("%cmd-no-args%", command.split(" ")[0]), discord_instance.getChannel(Configuration.instance().commandLog.channelID));
        }
        if (discord_instance != null) {
            boolean raw = false;

            if (((command.startsWith("say")) && Configuration.instance().messages.sendOnSayCommand) || (command.startsWith("me") && Configuration.instance().messages.sendOnMeCommand)) {
                String msg = command.replace("say ", "");
                if (command.startsWith("say"))
                    msg = msg.replaceFirst("say ", "");
                if (command.startsWith("me")) {
                    raw = true;
                    msg = "*" + MessageUtils.escapeMarkdown(msg.replaceFirst("me ", "").trim()) + "*";
                }
                discord_instance.sendMessage(ev.getParseResults().getContext().getSource().getTextName(), ev.getParseResults().getContext().getSource().getEntity().getUUID().toString(), new DiscordMessage(null, msg, !raw), discord_instance.getChannel(Configuration.instance().advanced.chatOutputChannelID));
            }
        }
    }

    @SubscribeEvent
    public void serverStopping(FMLServerStoppingEvent ev) {
        if (discord_instance != null) {
            discord_instance.sendMessage(Configuration.instance().localization.serverStopped);
            discord_instance.stopThreads();
        }
        this.stopped = true;
    }

    @SubscribeEvent
    public void serverStopped(FMLServerStoppedEvent ev) {
        if (discord_instance != null) {
            if (!stopped && discord_instance.getJDA() != null) ev.getServer().executeBlocking(() -> {
                discord_instance.stopThreads();
                try {
                    discord_instance.sendMessageReturns(Configuration.instance().localization.serverCrash, discord_instance.getChannel(Configuration.instance().advanced.serverChannelID)).get();
                } catch (InterruptedException | ExecutionException ignored) {
                }
            });
            discord_instance.kill();
        }
    }

    private boolean isModIDBlacklisted(String sender) {
        return ArrayUtils.contains(Configuration.instance().forgeSpecific.IMC_modIdBlacklist, sender);
    }

    //Still untested
    @SubscribeEvent
    public void imc(InterModProcessEvent ev) {
        final Stream<InterModComms.IMCMessage> stream = ev.getIMCStream();
        stream.forEach((msg) -> {
            System.out.println("[IMC-Message] Sender: " + msg.getSenderModId() + " method: " + msg.getMethod());
            if (isModIDBlacklisted(msg.getSenderModId())) return;
            if ((msg.getMethod().equals("Discord-Message") || msg.getMethod().equals("sendMessage"))) {
                discord_instance.sendMessage(msg.getMessageSupplier().get().toString());
            }
        });
    }

    @SubscribeEvent
    public void chat(ServerChatEvent ev) {
        if (PlayerLinkController.getSettings(null, ev.getPlayer().getUUID()).hideFromDiscord) return;
        final net.minecraft.network.chat.Component msg = ev.getComponent();
        if (discord_instance.callEvent((e) -> {
            if (e instanceof ForgeDiscordEventHandler) {
                return ((ForgeDiscordEventHandler) e).onMcChatMessage(ev);
            }
            return false;
        })) return;

        String text = MessageUtils.escapeMarkdown(ev.getMessage().replace("@everyone", "[at]everyone").replace("@here", "[at]here"));
        final MessageEmbed embed = ForgeMessageUtils.genItemStackEmbedIfAvailable(msg);
        if (discord_instance != null) {
            TextChannel channel = discord_instance.getChannel(Configuration.instance().advanced.chatOutputChannelID);
            if (channel == null) return;
            discord_instance.sendMessage(ForgeMessageUtils.formatPlayerName(ev.getPlayer()), ev.getPlayer().getUUID().toString(), new DiscordMessage(embed, text, true), channel);
            final String json = net.minecraft.network.chat.Component.Serializer.toJson(msg);
            Component comp = GsonComponentSerializer.gson().deserialize(json);
            final String editedJson = GsonComponentSerializer.gson().serialize(MessageUtils.mentionsToNames(comp, channel.getGuild()));
            ev.setComponent(net.minecraft.network.chat.Component.Serializer.fromJson(editedJson));
        }

    }

    @SubscribeEvent
    public void death(LivingDeathEvent ev) {
        if (PlayerLinkController.getSettings(null, ev.getEntity().getUUID()).hideFromDiscord) return;
        if (ev.getEntity() instanceof Player || (ev.getEntity() instanceof TamableAnimal && ((TamableAnimal) ev.getEntity()).getOwner() instanceof Player && Configuration.instance().messages.sendDeathMessagesForTamedAnimals)) {
            if (discord_instance != null) {
                final net.minecraft.network.chat.Component deathMessage = ev.getSource().getLocalizedDeathMessage(ev.getEntityLiving());
                final MessageEmbed embed = ForgeMessageUtils.genItemStackEmbedIfAvailable(deathMessage);
                discord_instance.sendMessage(new DiscordMessage(embed, Configuration.instance().localization.playerDeath.replace("%player%", ForgeMessageUtils.formatPlayerName(ev.getEntity())).replace("%msg%", ChatFormatting.stripFormatting(deathMessage.getString()).replace(ev.getEntity().getName().getContents() + " ", "")).replace("@everyone", "[at]everyone").replace("@here", "[at]here")), discord_instance.getChannel(Configuration.instance().advanced.deathsChannelID));
            }
        }
    }

    @SubscribeEvent
    public void playerLeave(PlayerEvent.PlayerLoggedOutEvent ev) {
        if (stopped) return; //Try to fix player leave messages after stop!
        if (PlayerLinkController.getSettings(null, ev.getPlayer().getUUID()).hideFromDiscord) return;
        if (discord_instance != null && !timeouts.contains(ev.getPlayer().getUUID()))
            discord_instance.sendMessage(Configuration.instance().localization.playerLeave.replace("%player%", ForgeMessageUtils.formatPlayerName(ev.getPlayer())));
        else if (discord_instance != null && timeouts.contains(ev.getPlayer().getUUID())) {
            discord_instance.sendMessage(Configuration.instance().localization.playerTimeout.replace("%player%", ForgeMessageUtils.formatPlayerName(ev.getPlayer())));
            timeouts.remove(ev.getPlayer().getUUID());
        }
    }
}
