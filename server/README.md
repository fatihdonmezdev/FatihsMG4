# FatihsMG4 consumption API

The Android app never connects to MongoDB directly and never contains a MongoDB URI. This
service accepts authenticated all-time totals and upserts them into the `consumption` collection.

## Run

1. Copy `.env.example` to `.env` and fill in the MongoDB Atlas URI and a random API token.
2. Export the variables (Node does not load `.env` automatically), then run `npm install` and
   `npm start`.
3. Build the Android app with the public HTTPS service address and the same token:

   ```sh
   CONSUMPTION_API_URL=https://your-api.example.com \
   CONSUMPTION_API_TOKEN=your-random-token \
   ./gradlew assembleUnstableRelease
   ```

If either Android build variable is missing, cloud sync is disabled and local consumption
tracking continues unchanged. MongoDB receives one continuously updated document per installation,
containing all-time `km`, `kwh`, `hours`, and `socDrop` totals plus the manually entered `sohPercent`.
