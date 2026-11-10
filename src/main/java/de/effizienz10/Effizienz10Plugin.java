package de.effizienz10;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.CommandExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Effizienz10Plugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final Enchantment EFF = Enchantment.EFFICIENCY;

    private int maxLevel;
    private boolean anvilEnabled;
    private boolean anvilCombining;
    private int xpCostPerLevel;
    private int maxXpCost;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("effbook");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info("Effizienz bis Stufe " + maxLevel + " aktiv.");
    }

    private void loadSettings() {
        reloadConfig();
        maxLevel = Math.max(1, Math.min(255, getConfig().getInt("max-level", 10)));
        anvilEnabled = getConfig().getBoolean("anvil.enabled", true);
        anvilCombining = getConfig().getBoolean("anvil.combine-books", true);
        xpCostPerLevel = Math.max(0, getConfig().getInt("anvil.xp-cost-per-level", 3));
        maxXpCost = Math.max(1, getConfig().getInt("anvil.max-xp-cost", 39));
    }

    // ------------------------------------------------------------------ Buch

    private ItemStack createBook(int level) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(EFF, level, true);
        book.setItemMeta(meta);
        return book;
    }

    private static int effLevel(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        if (item.getType() == Material.ENCHANTED_BOOK
                && item.getItemMeta() instanceof EnchantmentStorageMeta esm) {
            return esm.getStoredEnchantLevel(EFF);
        }
        return item.getEnchantmentLevel(EFF);
    }

    // ------------------------------------------------------------------ Amboss

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!anvilEnabled) return;

        AnvilInventory inv = event.getInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);
        if (left == null || right == null || left.getType().isAir()) return;
        if (right.getType() != Material.ENCHANTED_BOOK) return;
        if (left.getAmount() != 1) return;

        int rightLvl = effLevel(right);
        if (rightLvl <= 0) return;

        boolean leftIsBook = left.getType() == Material.ENCHANTED_BOOK;
        if (!leftIsBook && !EFF.canEnchantItem(left)) return;

        int leftLvl = effLevel(left);

        int newLvl;
        if (leftLvl == rightLvl && anvilCombining) {
            newLvl = leftLvl + 1;          // V+V = VI, VI+VI = VII, ... IX+IX = X
        } else {
            newLvl = Math.max(leftLvl, rightLvl);
        }
        newLvl = Math.min(newLvl, maxLevel);

        // Alles bis Stufe V macht Vanilla selbst
        if (newLvl <= EFF.getMaxLevel()) return;
        // Nichts würde sich ändern
        if (newLvl == leftLvl && leftLvl >= rightLvl && !hasOtherStored(right)) return;

        ItemStack vanillaResult = event.getResult();
        ItemStack result;
        if (vanillaResult != null && !vanillaResult.getType().isAir()) {
            result = vanillaResult.clone();
        } else {
            // Vanilla hat abgelehnt (z.B. "Zu teuer"): Buch darf dann nur Effizienz enthalten
            if (hasOtherStored(right)) return;
            result = left.clone();
            String rename = event.getView().getRenameText();
            if (rename != null && !rename.isBlank()) {
                ItemMeta meta = result.getItemMeta();
                meta.displayName(Component.text(rename));
                result.setItemMeta(meta);
            }
        }

        if (result.getType() == Material.ENCHANTED_BOOK) {
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) result.getItemMeta();
            meta.removeStoredEnchant(EFF);
            meta.addStoredEnchant(EFF, newLvl, true);
            result.setItemMeta(meta);
        } else {
            result.removeEnchantment(EFF);
            result.addUnsafeEnchantment(EFF, newLvl);
        }

        event.setResult(result);

        AnvilView view = event.getView();
        int cost = Math.max(1, Math.min(maxXpCost, xpCostPerLevel * newLvl));
        view.setMaximumRepairCost(Math.max(maxXpCost + 1, 40));
        view.setRepairCost(cost);
    }

    private static boolean hasOtherStored(ItemStack book) {
        if (!(book.getItemMeta() instanceof EnchantmentStorageMeta esm)) return false;
        for (Map.Entry<Enchantment, Integer> e : esm.getStoredEnchants().entrySet()) {
            if (!e.getKey().equals(EFF)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ Befehl

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("effizienz10.reload")) {
                sender.sendMessage(Component.text("Dazu hast du keine Rechte.", NamedTextColor.RED));
                return true;
            }
            loadSettings();
            sender.sendMessage(Component.text("Effizienz10 neu geladen (max. Stufe " + maxLevel + ").", NamedTextColor.GREEN));
            return true;
        }

        if (!sender.hasPermission("effizienz10.give")) {
            sender.sendMessage(Component.text("Dazu hast du keine Rechte.", NamedTextColor.RED));
            return true;
        }

        int level = maxLevel;
        if (args.length >= 1) {
            try {
                level = Integer.parseInt(args[0]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(Component.text("Benutzung: /" + label + " [stufe] [spieler]", NamedTextColor.RED));
                return true;
            }
            if (level < 1 || level > maxLevel) {
                sender.sendMessage(Component.text("Stufe muss zwischen 1 und " + maxLevel + " liegen.", NamedTextColor.RED));
                return true;
            }
        }

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Spieler " + args[1] + " ist nicht online.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Von der Konsole: /" + label + " <stufe> <spieler>", NamedTextColor.RED));
            return true;
        }

        var leftover = target.getInventory().addItem(createBook(level));
        leftover.values().forEach(i -> target.getWorld().dropItemNaturally(target.getLocation(), i));

        sender.sendMessage(Component.text("Effizienz " + level + " Buch an " + target.getName() + " gegeben.", NamedTextColor.GREEN));
        if (!sender.equals(target)) {
            target.sendMessage(Component.text("Du hast ein Effizienz " + level + " Buch erhalten.", NamedTextColor.GREEN));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("effizienz10.reload")) out.add("reload");
            for (int i = 1; i <= maxLevel; i++) out.add(String.valueOf(i));
            out.removeIf(s -> !s.startsWith(args[0]));
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
