FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# run as the unprivileged nginx user (Trivy DS002): this image listens on 8080 instead of 80
FROM nginxinc/nginx-unprivileged:1.28-alpine
# Alpine packages to their latest security fixes (openssl, brotli, c-ares) at every build
USER root
RUN apk upgrade --no-cache
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
# the base image already runs as nginx (uid 101); stated here so scanners and readers can see it
USER 101
EXPOSE 8080
