FROM node:22-alpine

WORKDIR /var/stremio_addon
# The exact files included are controlled by .dockerignore
COPY . .
RUN npm install --omit=dev --no-package-lock --no-audit --no-fund

CMD ["npm", "start"]
