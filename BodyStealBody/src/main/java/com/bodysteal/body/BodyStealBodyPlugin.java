package com.bodysteal.body;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bone.BoneTag;
import kr.toxicity.model.api.bone.BoneTags;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.entity.BaseEntity;
import kr.toxicity.model.api.profile.ModelProfile;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.TrackerModifier;
import kr.toxicity.model.api.tracker.TrackerUpdateAction;
import kr.toxicity.model.api.util.function.BonePredicate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Draws every player as a custom body (BetterModel). Lost limbs are hidden, a limb that was
 * stolen from another player is drawn with that player's skin. Reads the state from BodySteal.
 */
public class BodyStealBodyPlugin extends JavaPlugin implements Listener {

    private static final String BODY = "bodysteal_body";
    private static final String[] SLOTS = {"LEFT_ARM", "RIGHT_ARM", "LEFT_LEG", "RIGHT_LEG"};

    private Plugin bodySteal;
    private Method lostMethod, heldMethod, cagedMethod;
    private final Map<UUID, Rig> rigs = new HashMap<>();
    private boolean warnedMissingModel;

    /** The trackers (model instances) that belong to one player. */
    static final class Rig {
        EntityTracker main;
        final Map<String, EntityTracker> stolen = new HashMap<>();
        String sig;

        void closeStolen() {
            for (EntityTracker t : stolen.values()) {
                try { t.close(); } catch (RuntimeException ignored) { }
            }
            stolen.clear();
        }

        void closeAll() {
            closeStolen();
            if (main != null) {
                try { main.close(); } catch (RuntimeException ignored) { }
                main = null;
            }
        }
    }

    @Override
    public void onEnable() {
        bodySteal = Bukkit.getPluginManager().getPlugin("BodySteal");
        try {
            lostMethod = bodySteal.getClass().getMethod("getLostParts", UUID.class);
            heldMethod = bodySteal.getClass().getMethod("getHeldParts", UUID.class);
            cagedMethod = bodySteal.getClass().getMethod("isCaged", UUID.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().severe("This BodySteal build has no getLostParts/getHeldParts/isCaged. "
                    + "Build BodySteal from the newest source zip and try again.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        installModels();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::syncAll, 40L, 10L);
    }

    @Override
    public void onDisable() {
        for (Rig r : rigs.values()) r.closeAll();
        rigs.clear();
    }

    // ------------------------------------------------------------ model files

    static String stolenName(String slot) { return "bodysteal_stolen_" + slot.toLowerCase(); }

    /**
     * Copies the player body model into BetterModel/players (once). Models in that folder are
     * "player" models: BetterModel applies the real player skin to them. BetterModel needs
     * /bm reload after that.
     */
    private void installModels() {
        File base = new File(getDataFolder().getParentFile(), "BetterModel");
        File dir = new File(base, "players");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            getLogger().warning("Could not create " + dir);
            return;
        }
        List<String> names = new ArrayList<>();
        names.add(BODY);
        for (String s : SLOTS) names.add(stolenName(s));

        // Older versions of this plugin put the model in models/ (no skin there) - remove those copies.
        File oldDir = new File(base, "models");
        boolean removedOld = false;
        for (String n : names) {
            File old = new File(oldDir, n + ".bbmodel");
            if (old.isFile() && old.delete()) removedOld = true;
        }

        boolean copied = false;
        for (String n : names) {
            File f = new File(dir, n + ".bbmodel");
            if (f.exists()) continue;
            try (InputStream in = getResource("bodysteal_body.bbmodel")) {
                if (in == null) {
                    getLogger().warning("bodysteal_body.bbmodel is missing inside the plugin jar.");
                    return;
                }
                Files.copy(in, f.toPath());
                copied = true;
            } catch (IOException e) {
                getLogger().warning("Could not write " + f + ": " + e.getMessage());
            }
        }
        if (copied || removedOld) {
            getLogger().warning("Body models were installed into BetterModel/players. Run /bm reload once "
                    + "(or restart the server) so BetterModel loads them and rebuilds its resource pack.");
        }
    }

    /** Player models live in BetterModel's "limb" registry; fall back to the normal registry. */
    private static ModelRenderer renderer(String name) {
        ModelRenderer r = BetterModel.limbOrNull(name);
        return r != null ? r : BetterModel.modelOrNull(name);
    }

    // ------------------------------------------------------------ BodySteal state (reflection)

    @SuppressWarnings("unchecked")
    private Set<String> lost(UUID id) {
        try { return (Set<String>) lostMethod.invoke(bodySteal, id); }
        catch (ReflectiveOperationException e) { return Set.of(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> held(UUID id) {
        try { return (Map<String, String>) heldMethod.invoke(bodySteal, id); }
        catch (ReflectiveOperationException e) { return Map.of(); }
    }

    private boolean caged(UUID id) {
        try { return (Boolean) cagedMethod.invoke(bodySteal, id); }
        catch (ReflectiveOperationException e) { return false; }
    }

    // ------------------------------------------------------------ bones

    /** The bone of one limb; with children it also covers the forearm/foreleg and the held-item bone. */
    private static BonePredicate limb(String slot, boolean withChildren) {
        BoneTag tag = switch (slot) {
            case "LEFT_ARM" -> BoneTags.PLAYER_LEFT_ARM;
            case "RIGHT_ARM" -> BoneTags.PLAYER_RIGHT_ARM;
            case "LEFT_LEG" -> BoneTags.PLAYER_LEFT_LEG;
            default -> BoneTags.PLAYER_RIGHT_LEG;
        };
        var b = BonePredicate.tag(tag);
        return withChildren ? b.withChildren() : b.withoutChildren();
    }

    // ------------------------------------------------------------ syncing

    private void syncAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                sync(p);
            } catch (Exception e) {
                getLogger().warning("Body sync failed for " + p.getName() + ": " + e);
            }
        }
    }

    private void sync(Player p) {
        UUID id = p.getUniqueId();
        Set<String> lost = lost(id);
        Map<String, String> held = held(id);
        boolean caged = caged(id);

        Rig rig = rigs.computeIfAbsent(id, k -> new Rig());
        if (rig.main == null || rig.main.isClosed()) {
            rig.closeAll();
            rig.main = createMain(p);
            rig.sig = null;
            if (rig.main == null) return;
        }
        String sig = lost + "|" + held + "|" + caged;
        if (sig.equals(rig.sig)) return;                      // nothing changed
        rig.sig = sig;

        rig.closeStolen();
        boolean allGone = caged || lost.size() >= 4;          // lost everything = body hidden
        rig.main.update(TrackerUpdateAction.togglePart(!allGone), BonePredicate.TRUE);
        if (allGone) return;

        for (String slot : SLOTS) {
            boolean mine = !lost.contains(slot) && !held.containsKey(slot);
            if (!mine) rig.main.update(TrackerUpdateAction.togglePart(false), limb(slot, true));
            String h = held.get(slot);
            if (h != null) {
                EntityTracker st = createStolen(p, slot, UUID.fromString(h.split(":")[0]));
                if (st != null) rig.stolen.put(slot, st);
            }
        }
    }

    /** The player's full body, drawn with their own skin. */
    private EntityTracker createMain(Player p) {
        ModelRenderer r = renderer(BODY);
        if (r == null) {
            warnMissing();
            return null;
        }
        return r.getOrCreate(BaseEntity.of(BukkitAdapter.adapt(p)),
                ModelProfile.of(BukkitAdapter.adapt(p)), TrackerModifier.DEFAULT, t -> { });
    }

    /** One limb only, drawn with the skin of the player it was stolen from. */
    private EntityTracker createStolen(Player p, String slot, UUID owner) {
        ModelRenderer r = renderer(stolenName(slot));
        if (r == null) {
            warnMissing();
            return null;
        }
        return r.create(BaseEntity.of(BukkitAdapter.adapt(p)), ModelProfile.of(owner),
                TrackerModifier.DEFAULT, t -> {
                    t.update(TrackerUpdateAction.togglePart(false), BonePredicate.TRUE);
                    t.update(TrackerUpdateAction.togglePart(true), limb(slot, true));
                });
    }

    private void warnMissing() {
        if (warnedMissingModel) return;
        warnedMissingModel = true;
        getLogger().warning("BetterModel does not know the model '" + BODY + "' yet. Run /bm reload "
                + "and check that plugins/BetterModel/players contains bodysteal_body.bbmodel.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Rig r = rigs.remove(e.getPlayer().getUniqueId());
        if (r != null) r.closeAll();
    }
                }
            
