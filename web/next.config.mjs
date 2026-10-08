/** @type {import('next').NextConfig} */
const nextConfig = {
  // Every page reads live telemetry; a cached shell would show yesterday's drive.
  experimental: { serverActions: { bodySizeLimit: "1mb" } },
};
export default nextConfig;
