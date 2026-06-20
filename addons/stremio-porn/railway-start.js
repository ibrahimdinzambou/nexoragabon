const publicDomain = process.env.RAILWAY_PUBLIC_DOMAIN
  || process.env.RAILWAY_STATIC_URL
  || process.env.RAILWAY_PUBLIC_URL;

if (!process.env.NODE_ENV) {
  process.env.NODE_ENV = 'production';
}

if (!process.env.STREMIO_PORN_ID) {
  process.env.STREMIO_PORN_ID = 'com.nexora.stremio-porn';
}

if (!process.env.STREMIO_PORN_ENDPOINT) {
  let endpoint = publicDomain || '';
  if (endpoint && !/^https?:\/\//i.test(endpoint)) {
    endpoint = `https://${endpoint}`;
  }
  process.env.STREMIO_PORN_ENDPOINT = endpoint || `http://localhost:${process.env.PORT || process.env.STREMIO_PORN_PORT || '80'}`;
}

if (!process.env.STREMIO_PORN_PORT && process.env.PORT) {
  process.env.STREMIO_PORN_PORT = process.env.PORT;
}

require('./dist/index.js');
