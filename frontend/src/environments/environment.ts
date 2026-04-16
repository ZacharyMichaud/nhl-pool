export const environment = {
  production: false,
  apiUrl: (typeof window !== 'undefined' && window.location.hostname !== 'localhost')
    ? 'https://nhl-pool.onrender.com/api'
    : 'http://localhost:8080/api',
};
