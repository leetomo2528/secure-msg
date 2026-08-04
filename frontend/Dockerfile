# Frontend build → static files served by Caddy
FROM node:20-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM caddy:2-alpine AS serve
COPY --from=build /app/dist /srv
COPY Caddyfile.frontend /etc/caddy/Caddyfile
