export const environment = {
  production: true,
  apiUrl: 'https://nhl-pool-production-edb9.up.railway.app/api',
  // Vercel cannot proxy WebSocket upgrades, so we connect directly to Railway
  wsUrl: 'wss://nhl-pool-production-edb9.up.railway.app/ws-native',
};
