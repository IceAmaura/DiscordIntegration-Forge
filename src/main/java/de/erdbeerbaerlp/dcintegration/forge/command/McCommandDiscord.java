package de.erdbeerbaerlp.dcintegration.forge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.erdbeerbaerlp.dcintegration.common.addon.AddonLoader;
import de.erdbeerbaerlp.dcintegration.common.storage.Configuration;
import de.erdbeerbaerlp.dcintegration.common.storage.PlayerLinkController;
import de.erdbeerbaerlp.dcintegration.common.util.Variables;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.*;
import net.minecraftforge.fmllegacy.server.ServerLifecycleHooks;

import java.io.IOException;


public class McCommandDiscord {
    public McCommandDiscord(CommandDispatcher<CommandSourceStack> dispatcher) {
        final LiteralArgumentBuilder<CommandSourceStack> l = Commands.literal("discord");
        if (Configuration.instance().ingameCommand.enabled) l.executes((ctx) -> {
            ctx.getSource().sendSuccess(ComponentUtils.mergeStyles(new TextComponent(Configuration.instance().ingameCommand.message),
                    Style.EMPTY.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponent(Configuration.instance().ingameCommand.hoverMessage)))
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, Configuration.instance().ingameCommand.inviteURL))), false);
            return 0;
        });
        l.then(Commands.literal("restart").requires((p) -> p.hasPermission(3)).executes((ctx) -> {
            new Thread(() -> {
                if (Variables.discord_instance.restart()) {
                    ctx.getSource().sendSuccess(new TextComponent(ChatFormatting.GREEN + "Discord bot restarted!"), true);
                } else
                    ctx.getSource().sendFailure(new TextComponent(ChatFormatting.RED + "Failed to properly restart the discord bot!"));
            }).start();
            return 0;
        })).then(Commands.literal("ignore").executes((ctx) -> {
            ctx.getSource().sendSuccess(
                    new TextComponent(Variables.discord_instance.togglePlayerIgnore(ctx.getSource().getPlayerOrException().getUUID()) ? Configuration.instance().localization.commands.commandIgnore_unignore : Configuration.instance().localization.commands.commandIgnore_ignore), true);
            return 0;
        })).then(Commands.literal("link").executes((ctx) -> {
            if (Configuration.instance().linking.enableLinking && ServerLifecycleHooks.getCurrentServer().usesAuthentication() && !Configuration.instance().linking.whitelistMode) {
                if (PlayerLinkController.isPlayerLinked(ctx.getSource().getPlayerOrException().getUUID())) {
                    ctx.getSource().sendSuccess(new TextComponent(ChatFormatting.RED + Configuration.instance().localization.linking.alreadyLinked.replace("%player%", Variables.discord_instance.getJDA().getUserById(PlayerLinkController.getDiscordFromBedrockPlayer(ctx.getSource().getPlayerOrException().getUUID())).getAsTag())), false);
                    return 0;
                }
                final int r = Variables.discord_instance.genLinkNumber(ctx.getSource().getPlayerOrException().getUUID());
                ctx.getSource().sendSuccess(ComponentUtils.mergeStyles(new TextComponent(Configuration.instance().localization.linking.linkMsgIngame.replace("%num%", r + "").replace("%prefix%", "/")), Style.EMPTY.applyFormat(ChatFormatting.AQUA).withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, "" + r)).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponent(Configuration.instance().localization.linking.hoverMsg_copyClipboard)))), false);
            } else {
                ctx.getSource().sendSuccess(new TextComponent(ChatFormatting.RED + Configuration.instance().localization.commands.subcommandDisabled), false);
            }
            return 0;
        })).then(Commands.literal("stop").requires((p) -> p.hasPermission(4)).executes((ctx) -> {
            Variables.discord_instance.kill();
            ctx.getSource().sendSuccess(new TextComponent("DiscordIntegration was successfully stopped!"), false);
            return 0;
        })).then(Commands.literal("reload").requires((p) -> p.hasPermission(4)).executes((ctx) -> {
            try {
                Configuration.instance().loadConfig();
            } catch (IOException e) {
                ctx.getSource().sendSuccess(new TextComponent(e.getMessage()).setStyle(Style.EMPTY.applyFormat(ChatFormatting.RED)),true);
                e.printStackTrace();
            }
            AddonLoader.reloadAll();
            ctx.getSource().sendSuccess(new TextComponent(Configuration.instance().localization.commands.configReloaded), true);
            return 0;
        }));
        dispatcher.register(l);
    }
}
