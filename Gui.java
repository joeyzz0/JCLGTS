package net.joeyscobblemon.gts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/** Chest-style menus. They run entirely on the server, so players see a normal chest screen. */
public final class Gui {
    private Gui() {}

    public record Click(ServerPlayer player, ClickType type, int button) {}

    public static final class Screen {
        final Component title;
        final String titleKey;
        final int rows;
        final ItemStack[] icons;
        final Map<Integer, Consumer<Click>> actions = new HashMap<>();

        Screen(String title, int rows) {
            this.title = Component.literal(title);
            this.titleKey = title;
            this.rows = rows;
            this.icons = new ItemStack[rows * 9];
            for (int i = 0; i < icons.length; i++) icons[i] = ItemStack.EMPTY;
        }

        void set(int slot, ItemStack s) { icons[slot] = s; }
        void set(int slot, ItemStack s, Consumer<Click> action) { icons[slot] = s; actions.put(slot, action); }

        void border(ItemStack pane) {
            for (int i = 0; i < icons.length; i++) {
                int r = i / 9, c = i % 9;
                if (r == 0 || r == rows - 1 || c == 0 || c == 8) icons[i] = pane.copy();
            }
        }
    }

    static final class GtsMenu extends ChestMenu {
        final int rowCount;
        final SimpleContainer box;
        Screen screen;

        GtsMenu(int id, Inventory inv, SimpleContainer box, int rows, Screen screen) {
            super(typeFor(rows), id, inv, box, rows);
            this.rowCount = rows;
            this.box = box;
            this.screen = screen;
        }

        static MenuType<?> typeFor(int rows) {
            return switch (rows) {
                case 1 -> MenuType.GENERIC_9x1;
                case 2 -> MenuType.GENERIC_9x2;
                case 3 -> MenuType.GENERIC_9x3;
                case 4 -> MenuType.GENERIC_9x4;
                case 5 -> MenuType.GENERIC_9x5;
                default -> MenuType.GENERIC_9x6;
            };
        }

        void apply(Screen s) {
            this.screen = s;
            for (int i = 0; i < rowCount * 9; i++) box.setItem(i, i < s.icons.length ? s.icons[i] : ItemStack.EMPTY);
            box.setChanged();
            broadcastChanges();
        }

        @Override
        public void clicked(int slotId, int button, ClickType type, Player player) {
            setCarried(ItemStack.EMPTY);
            if (slotId >= 0 && slotId < rowCount * 9 && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE)) {
                Consumer<Click> a = screen.actions.get(slotId);
                if (a != null && player instanceof ServerPlayer sp) {
                    try { a.accept(new Click(sp, type, button)); }
                    catch (Throwable t) { GtsMod.LOG.error("[GTS] Menu click failed", t); sp.sendSystemMessage(Fmt.chat("[GTS] Something went wrong. Try again.", ChatFormatting.RED)); }
                }
            }
            sendAllDataToRemote();
        }

        @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player p) { return true; }
    }

    static void open(ServerPlayer p, Screen s) {
        if (p.containerMenu instanceof GtsMenu m && m.rowCount == s.rows && m.screen.titleKey.equals(s.titleKey)) {
            m.apply(s);
            return;
        }
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> {
            SimpleContainer box = new SimpleContainer(s.rows * 9);
            GtsMenu m = new GtsMenu(id, inv, box, s.rows, s);
            m.apply(s);
            return m;
        }, s.title));
    }

    // ------------------------------------------------------------------ item builders

    static ItemStack icon(net.minecraft.world.item.Item item, String name, ChatFormatting color, String... lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Fmt.line(name, color));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String x : lore) l.add(Fmt.line(x));
            s.set(DataComponents.LORE, new ItemLore(l));
        }
        s.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, net.minecraft.util.Unit.INSTANCE);
        return s;
    }

    static ItemStack pane(net.minecraft.world.item.Item item) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        s.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, net.minecraft.util.Unit.INSTANCE);
        return s;
    }

    /** The listing's item with the market info added under its own tooltip. */
    static ItemStack display(Listing l, boolean detailed) {
        ItemStack s = Market.decode(l.itemSnbt);
        if (s.isEmpty()) s = new ItemStack(Items.BARRIER);
        if (l.kind == Listing.Kind.POKEMON) s.set(DataComponents.CUSTOM_NAME, Component.literal(l.title).withStyle(st -> st.withItalic(false)));
        List<Component> lore = new ArrayList<>();
        ItemLore old = s.get(DataComponents.LORE);
        if (l.kind == Listing.Kind.ITEM && old != null) lore.addAll(old.lines());
        lore.add(Fmt.line(" "));
        lore.add(Fmt.line("§8▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
        if (l.kind == Listing.Kind.POKEMON) for (String d : l.details) lore.add(Fmt.line(d));
        lore.add(Fmt.line("§7Seller: §f" + l.sellerName));
        long now = System.currentTimeMillis();
        if (l.mode == Listing.Mode.AUCTION) {
            lore.add(Fmt.line("§6§lAUCTION"));
            if (l.bidder == null) lore.add(Fmt.line("§7Starting bid: §e" + Fmt.cd(l.price)));
            else {
                lore.add(Fmt.line("§7Top bid: §e" + Fmt.cd(l.currentBid) + " §7by §f" + l.bidderName));
                lore.add(Fmt.line("§7Bids: §f" + l.bidCount));
            }
            lore.add(Fmt.line("§7Ends in: §f" + Fmt.duration(l.endsAt - now)));
        } else {
            lore.add(Fmt.line("§a§lBUY IT NOW"));
            lore.add(Fmt.line("§7Price: §e" + Fmt.cd(l.price)));
            lore.add(Fmt.line("§7Expires in: §f" + Fmt.duration(l.endsAt - now)));
        }
        lore.add(Fmt.line("§8ID #" + l.id));
        if (!detailed) { lore.add(Fmt.line(" ")); lore.add(Fmt.line("§e▶ Click to open", ChatFormatting.YELLOW)); }
        s.set(DataComponents.LORE, new ItemLore(lore));
        return s;
    }

    // ------------------------------------------------------------------ browse

    static final class View {
        int page;
        Market.Filter filter = Market.Filter.ALL;
        Market.Sort sort = Market.Sort.ENDING_SOON;
    }

    private static final Map<UUID, View> VIEWS = new HashMap<>();
    static View view(ServerPlayer p) { return VIEWS.computeIfAbsent(p.getUUID(), k -> new View()); }

    private static String filterName(Market.Filter f) {
        return switch (f) { case ALL -> "Everything"; case POKEMON -> "Pokémon"; case ITEMS -> "Items"; case AUCTIONS -> "Auctions"; case BUY_NOW -> "Buy It Now"; };
    }
    private static String sortName(Market.Sort s) {
        return switch (s) { case NEWEST -> "Newest"; case ENDING_SOON -> "Ending soon"; case PRICE_LOW -> "Price: low to high"; case PRICE_HIGH -> "Price: high to low"; };
    }

    public static void browse(ServerPlayer p) {
        View v = view(p);
        List<Listing> all = Market.query(v.filter, v.sort, null);
        int pages = Math.max(1, (all.size() + 44) / 45);
        if (v.page >= pages) v.page = pages - 1;
        if (v.page < 0) v.page = 0;

        Screen s = new Screen("§6§l⚑ Global Trade System", 6);
        int from = v.page * 45;
        for (int i = 0; i < 45 && from + i < all.size(); i++) {
            Listing l = all.get(from + i);
            s.set(i, display(l, false), c -> detail(c.player(), l.id, false));
        }
        if (all.isEmpty()) s.set(22, icon(Items.BARRIER, "Nothing here yet", ChatFormatting.GRAY, "§7Be the first to list something!", "§7Use §f/gts sell §7or §f/gts auction"));

        ItemStack dark = pane(Items.BLACK_STAINED_GLASS_PANE);
        for (int i = 45; i < 54; i++) s.set(i, dark.copy());
        s.set(45, icon(Items.ARROW, "◀ Previous page", ChatFormatting.YELLOW), c -> { v.page--; browse(c.player()); });
        s.set(53, icon(Items.ARROW, "Next page ▶", ChatFormatting.YELLOW), c -> { v.page++; browse(c.player()); });
        s.set(46, icon(Items.HOPPER, "Filter: " + filterName(v.filter), ChatFormatting.AQUA, "§7Click to change", "§8Everything, Pokémon, Items, Auctions, Buy It Now"),
                c -> { Market.Filter[] f = Market.Filter.values(); v.filter = f[(v.filter.ordinal() + 1) % f.length]; v.page = 0; browse(c.player()); });
        s.set(47, icon(Items.COMPARATOR, "Sort: " + sortName(v.sort), ChatFormatting.AQUA, "§7Click to change"),
                c -> { Market.Sort[] f = Market.Sort.values(); v.sort = f[(v.sort.ordinal() + 1) % f.length]; v.page = 0; browse(c.player()); });
        s.set(48, icon(Items.EMERALD, "Sell something", ChatFormatting.GREEN, "§7List a Pokémon or item", "§7for Buy It Now or auction"), c -> sellMenu(c.player()));
        s.set(49, icon(Items.BOOK, "Page " + (v.page + 1) + " of " + pages, ChatFormatting.GOLD, "§7" + all.size() + " listings",
                "§8/gts sell <price> [hours]", "§8/gts sellpokemon <slot> <price> [hours]", "§8/gts auction <start> <hours>", "§8/gts auctionpokemon <slot> <start> <hours>", "§8/gts mine   /gts claims"), c -> {});
        s.set(50, icon(Items.NAME_TAG, "My listings", ChatFormatting.LIGHT_PURPLE, "§7You have §f" + Market.countOf(p.getUUID()) + "§7/" + GtsConfig.INSTANCE.maxListingsPerPlayer), c -> mine(c.player()));
        int cl = Market.claimCount(p.getUUID());
        s.set(51, icon(cl > 0 ? Items.CHEST_MINECART : Items.CHEST, "Claims" + (cl > 0 ? " §e(" + cl + ")" : ""), ChatFormatting.GOLD,
                cl > 0 ? "§eThings are waiting for you!" : "§7Nothing waiting"), c -> claims(c.player()));
        s.set(52, icon(Items.CLOCK, "Refresh", ChatFormatting.GRAY), c -> browse(c.player()));
        open(p, s);
    }

    // ------------------------------------------------------------------ detail

    static void detail(ServerPlayer p, String id, boolean fromMine) {
        Listing l = Market.data.listings.get(id);
        if (l == null) { p.sendSystemMessage(Fmt.chat("[GTS] That listing is gone.", ChatFormatting.RED)); browse(p); return; }
        Screen s = new Screen("§6Listing #" + l.id, 4);
        s.border(pane(Items.GRAY_STAINED_GLASS_PANE));
        s.set(4, display(l, true));
        boolean owner = l.seller.equals(p.getUUID());
        boolean admin = p.hasPermissions(2);
        Runnable back = () -> { if (fromMine) mine(p); else browse(p); };

        if (l.mode == Listing.Mode.BUY_NOW) {
            if (owner) s.set(13, icon(Items.GRAY_DYE, "This is your listing", ChatFormatting.GRAY, "§7You can cancel it below"));
            else s.set(13, icon(Items.EMERALD_BLOCK, "Buy now for " + Fmt.cd(l.price), ChatFormatting.GREEN, "§7Click to confirm the purchase"), c -> {
                String err = Market.buy(c.player(), l.id);
                if (err != null) c.player().sendSystemMessage(Fmt.chat("[GTS] " + err, ChatFormatting.RED));
                else c.player().sendSystemMessage(Fmt.chat("[GTS] Purchase complete! " + (l.kind == Listing.Kind.POKEMON ? "Check your party or PC." : "Check your inventory."), ChatFormatting.GREEN));
                browse(c.player());
            });
        } else {
            long min = Market.minNextBid(l);
            if (owner) s.set(13, icon(Items.GRAY_DYE, "This is your auction", ChatFormatting.GRAY, "§7Top bid: " + (l.bidder == null ? "none" : Fmt.cd(l.currentBid))));
            else {
                long[] amounts = { min, Math.max(min, Market.shownPrice(l) * 110 / 100), Math.max(min, Market.shownPrice(l) * 125 / 100), Math.max(min, Market.shownPrice(l) * 150 / 100), Math.max(min, Market.shownPrice(l) * 200 / 100) };
                String[] names = { "Minimum bid", "Bid +10%", "Bid +25%", "Bid +50%", "Bid +100%" };
                int[] slots = { 11, 12, 13, 14, 15 };
                net.minecraft.world.item.Item[] mats = { Items.GOLD_NUGGET, Items.GOLD_INGOT, Items.GOLD_BLOCK, Items.DIAMOND, Items.DIAMOND_BLOCK };
                for (int i = 0; i < slots.length; i++) {
                    final long amt = amounts[i];
                    s.set(slots[i], icon(mats[i], names[i] + ": " + Fmt.cd(amt), ChatFormatting.GOLD, "§7Click to bid", "§8Your CobbleDollars are held until you win or are outbid"), c -> {
                        String err = Market.bid(c.player(), l.id, amt);
                        if (err != null) c.player().sendSystemMessage(Fmt.chat("[GTS] " + err, ChatFormatting.RED));
                        else c.player().sendSystemMessage(Fmt.chat("[GTS] Bid placed: " + Fmt.cd(amt), ChatFormatting.GREEN));
                        detail(c.player(), l.id, fromMine);
                    });
                }
            }
        }
        if ((owner && !(l.mode == Listing.Mode.AUCTION && l.bidder != null)) || admin) {
            s.set(22, icon(Items.RED_CONCRETE, owner ? "Cancel listing" : "Remove listing (admin)", ChatFormatting.RED, "§7Returns the goods to the seller's claims"), c -> {
                String err = Market.cancel(c.player(), l.id, !owner && admin);
                c.player().sendSystemMessage(err != null ? Fmt.chat("[GTS] " + err, ChatFormatting.RED) : Fmt.chat("[GTS] Listing cancelled. Collect it with /gts claims.", ChatFormatting.YELLOW));
                back.run();
            });
        }
        s.set(27, icon(Items.ARROW, "◀ Back", ChatFormatting.YELLOW), c -> back.run());
        s.set(35, icon(Items.CLOCK, "Refresh", ChatFormatting.GRAY), c -> detail(c.player(), l.id, fromMine));
        open(p, s);
    }

    // ------------------------------------------------------------------ my listings, claims, sell

    public static void mine(ServerPlayer p) {
        List<Listing> list = Market.query(Market.Filter.ALL, Market.Sort.ENDING_SOON, p.getUUID());
        Screen s = new Screen("§dMy listings", 6);
        for (int i = 0; i < 45 && i < list.size(); i++) {
            Listing l = list.get(i);
            s.set(i, display(l, false), c -> detail(c.player(), l.id, true));
        }
        if (list.isEmpty()) s.set(22, icon(Items.BARRIER, "You have no listings", ChatFormatting.GRAY));
        ItemStack dark = pane(Items.BLACK_STAINED_GLASS_PANE);
        for (int i = 45; i < 54; i++) s.set(i, dark.copy());
        s.set(45, icon(Items.ARROW, "◀ Back to market", ChatFormatting.YELLOW), c -> browse(c.player()));
        s.set(49, icon(Items.NAME_TAG, list.size() + " / " + GtsConfig.INSTANCE.maxListingsPerPlayer + " listings", ChatFormatting.LIGHT_PURPLE));
        open(p, s);
    }

    public static void claims(ServerPlayer p) {
        List<ClaimEntry> list = Market.data.claims.getOrDefault(p.getUUID().toString(), new ArrayList<>());
        Screen s = new Screen("§6Claims", 4);
        s.border(pane(Items.GRAY_STAINED_GLASS_PANE));
        int slot = 10;
        for (ClaimEntry c : list) {
            if (slot > 25) break;
            if (slot % 9 == 8) slot += 2;
            ItemStack st;
            if (c.type == ClaimEntry.Type.MONEY) st = icon(Items.GOLD_INGOT, c.title, ChatFormatting.GOLD, "§7" + c.reason);
            else {
                st = Market.decode(c.itemSnbt);
                if (st.isEmpty()) st = new ItemStack(Items.BARRIER);
                st.set(DataComponents.LORE, new ItemLore(List.of(Fmt.line("§7" + c.reason))));
                if (c.type == ClaimEntry.Type.POKEMON) st.set(DataComponents.CUSTOM_NAME, Component.literal(c.title).withStyle(x -> x.withItalic(false)));
            }
            s.set(slot++, st);
        }
        if (list.isEmpty()) s.set(13, icon(Items.BARRIER, "Nothing is waiting for you", ChatFormatting.GRAY));
        else s.set(31, icon(Items.HOPPER, "Claim everything (" + list.size() + ")", ChatFormatting.GREEN, "§7Items go to your inventory, Pokémon to your party or PC", "§7Anything that doesn't fit stays here"), c -> {
            int n = Market.claimAll(c.player());
            c.player().sendSystemMessage(Fmt.chat("[GTS] Collected " + n + " " + (n == 1 ? "entry" : "entries") + ".", ChatFormatting.GREEN));
            claims(c.player());
        });
        s.set(27, icon(Items.ARROW, "◀ Back", ChatFormatting.YELLOW), c -> browse(c.player()));
        open(p, s);
    }

    static void sellMenu(ServerPlayer p) {
        Screen s = new Screen("§aSell something", 4);
        s.border(pane(Items.LIME_STAINED_GLASS_PANE));
        s.set(4, icon(Items.EMERALD, "What do you want to sell?", ChatFormatting.GREEN, "§7Click a Pokémon or the item in your hand,", "§7then follow the clickable message in chat"));
        try {
            if (CobblemonBridge.available()) {
                Object party = CobblemonBridge.party(p);
                for (int i = 0; i < 6; i++) {
                    Object pk = CobblemonBridge.partyGet(party, i);
                    int slot = 10 + i;
                    final int num = i + 1;
                    if (pk == null) { s.set(slot, icon(Items.GRAY_STAINED_GLASS_PANE, "Empty slot " + num, ChatFormatting.DARK_GRAY)); continue; }
                    CobblemonBridge.Snap snap = CobblemonBridge.snapshot(pk, Market.server.registryAccess());
                    ItemStack st = snap.icon.copy();
                    st.set(DataComponents.CUSTOM_NAME, Component.literal(snap.title).withStyle(x -> x.withItalic(false)));
                    List<Component> lore = new ArrayList<>();
                    for (String line : snap.lines) lore.add(Fmt.line(line));
                    lore.add(Fmt.line(" "));
                    lore.add(Fmt.line("§eClick to list this Pokémon"));
                    st.set(DataComponents.LORE, new ItemLore(lore));
                    s.set(slot, st, c -> {
                        c.player().closeContainer();
                        prompt(c.player(), "Pokémon in slot " + num, "/gts sellpokemon " + num + " ", "/gts auctionpokemon " + num + " ");
                    });
                }
            }
        } catch (Throwable t) {
            GtsMod.LOG.error("[GTS] Could not show the party in the sell menu", t);
        }
        ItemStack held = p.getMainHandItem();
        if (!held.isEmpty()) {
            ItemStack st = held.copy();
            List<Component> lore = new ArrayList<>();
            ItemLore old = st.get(DataComponents.LORE);
            if (old != null) lore.addAll(old.lines());
            lore.add(Fmt.line(" "));
            lore.add(Fmt.line("§eClick to list the item in your hand"));
            st.set(DataComponents.LORE, new ItemLore(lore));
            s.set(22, st, c -> {
                c.player().closeContainer();
                prompt(c.player(), "the item in your hand", "/gts sell ", "/gts auction ");
            });
        } else {
            s.set(22, icon(Items.GRAY_DYE, "No item in your hand", ChatFormatting.GRAY, "§7Hold the item you want to sell,", "§7then open this menu again"));
        }
        s.set(27, icon(Items.ARROW, "◀ Back", ChatFormatting.YELLOW), c -> browse(c.player()));
        open(p, s);
    }

    static void prompt(ServerPlayer p, String what, String binCmd, String aucCmd) {
        p.sendSystemMessage(Fmt.chat("[GTS] ", ChatFormatting.GOLD).append(Fmt.chat("Listing " + what + ". Click one, then type the price:", ChatFormatting.YELLOW)));
        p.sendSystemMessage(Component.literal("  ").append(
                Component.literal("[Buy It Now]").withStyle(st -> st.withColor(ChatFormatting.GREEN).withBold(true)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, binCmd))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal("Type the price after the command: <price> [hours]")))))
                .append(Component.literal("   "))
                .append(Component.literal("[Auction]").withStyle(st -> st.withColor(ChatFormatting.GOLD).withBold(true)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, aucCmd))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal("Type the start price and hours: <start> <hours>"))))));
    }
}
