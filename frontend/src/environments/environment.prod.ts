export const environment = {
  production: true,
  apiUrl: 'https://nhl-pool.onrender.com/api',
  // Vercel cannot proxy WebSocket upgrades, so we connect directly to Render
  wsUrl: 'wss://nhl-pool.onrender.com/ws-native',
};
