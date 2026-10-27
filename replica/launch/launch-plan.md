# Launch plan (0.2.x)

1. Deploy the site (owner runs `vercel --prod --yes` from site/public; the agent may not deploy).
2. Store listing text: replica/launch/listing.json (linted: 0 errors). Google Play needs the $25
   developer account (docs/STORES.md); App Store needs the $99 program. Owner's decision.
3. Where unhappy users already talk: Hacker News threads about phones as controllers (109 comments in the
   research sample, links in replica/reviews.csv, kept local). Reddit was not sampled.
4. A Show HN post that leads with the fix: "My Android phone pairs as an Xbox controller from the Mac's
   Bluetooth settings — no PC program." Link the test evidence, not adjectives.
5. First 10 users by hand: friends with a Mac or a Windows PC and an Android phone; watch them pair
   without help and note where the connection check is needed.
6. No analytics are added (product promise). Ask testers directly instead.
