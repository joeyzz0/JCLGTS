# Joey's Global Trade System (jcl_gts)

A chest-menu Global Trade System for Cobblemon servers on NeoForge 1.21.1.
Buy It Now and timed auctions for Pokémon and items, paid in CobbleDollars.

## Commands
| Command | What it does |
|---|---|
| `/gts` | Open the market |
| `/gts sell <price> [hours]` | Sell the item in your main hand (Buy It Now, default 72h, max 168h) |
| `/gts sellpokemon <slot 1-6> <price> [hours]` | Sell the Pokémon in a party slot |
| `/gts auction <start price> <hours>` | Auction the item in your hand (1 to 72h) |
| `/gts auctionpokemon <slot 1-6> <start price> <hours>` | Auction a Pokémon |
| `/gts mine` | Your listings (click one to cancel it) |
| `/gts claims` / `/gts claim` | Collect anything waiting for you |
| `/gts admin remove <id>` | Remove any listing (op) |
| `/gts admin webhooktest` | Send a test message to Discord (op) |
| `/gts admin reload` | Reload `config/jcl_gts.json` (op) |
| `/gts admin balance` | Show what the mod reads as your CobbleDollars balance (op) |

## How it works
* **Escrow.** Goods leave the seller the moment they list. Bids are paid up front and refunded automatically when outbid.
* **Offline players.** Money, items and Pokémon wait in `/gts claims`. Money for online players is paid straight away.
* **Auctions.** Minimum raise is 5% (at least 1 CD). A bid in the last 2 minutes extends the auction by 2 minutes (up to 10 times).
* **Pokémon.** Listings show species, level, shiny, gender, nature, ability, IVs (and total), EVs (and total), moves, held item and ball.
* **Eggs.** Brecher's eggs are listed as items, and their tooltip (nature, IVs, ability, moves) shows in the menu. An egg whose contents are still hidden can't be listed.
* **Blocked items.** Master Ball, Ancient Origin Ball, EV feathers and the Cobblemon Utility+ restricted items. Edit `restrictedItems` in the config.
* **Limits.** 20 listings per player, prices 1 to 1,000,000,000, no fees.
* **Discord.** Put a webhook URL in `config/jcl_gts.json`. New listings, sales, auction results, "ending soon" alerts, and one live board message that is edited every 5 minutes.
* **Saves.** `<world>/data/jcl_gts/market.json`, written atomically.

## Layout
All files sit in one folder on purpose (no src/ folders), so they can be uploaded to GitHub in one drag.

## Building
1. Install JDK 21.
2. In this folder run `gradle wrapper` once (needs Gradle 8.10 or newer), then `./gradlew build`.
   Or open the folder in IntelliJ IDEA and let it import the Gradle project.
3. The jar is in `build/libs/jcl_gts-1.0.0.jar`. Put it in the server's `mods` folder (and in the client pack too).
4. If Gradle can't find plugin version `2.0.78`, use the newest `net.neoforged.moddev` 2.x listed at https://plugins.gradle.org.

## First test checklist
1. `/gts admin balance` should print your real CobbleDollars number. If it prints -1 or a wrong number, check the server log for the line starting `[GTS] Could not read a balance` and set `balanceRegex` in the config.
2. List an item with `/gts sell 100`, open `/gts` as a second player, buy it, check money moved.
3. List a Pokémon, buy it, check it arrives in the party (or PC if full).
4. Start a 1 hour auction, bid with two players, check refunds.
5. `/gts admin webhooktest` once you have set `webhookUrl`.

## Things written without being able to run them
Cobblemon is reached through reflection so the mod builds without it. If a Pokémon detail line is missing in the menu, or the Pokémon icon shows a Poké Ball instead of the model, the server log names the part that failed (look for `[GTS] Could not read`).

## Getting the jar without installing anything
Push this folder to a GitHub repository. The included workflow (`.github/workflows/build.yml`) builds it on GitHub's servers.
Open the repository's **Actions** tab, click the latest run, and download the **jcl_gts-jar** artifact. The jar inside goes in your `mods` folder.
If the build fails, open the run and send me the red error text.
