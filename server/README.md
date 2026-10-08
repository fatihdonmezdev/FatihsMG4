# FatihsMG4 consumption API

The Android app never connects to MongoDB directly and never contains a MongoDB URI. This
service stores completed daily totals in `daily_consumption` and each completed charge,
including its SOC/power curve and price, in `charging_sessions`.

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
tracking continues unchanged. The car retries locally queued charging sessions every five minutes.
Daily consumption is uploaded once after the day closes; missed days are retried in order.

The future mobile client can read authenticated history from:

- `GET /v1/consumption/:installationId`
- `GET /v1/charging-sessions/:installationId`
