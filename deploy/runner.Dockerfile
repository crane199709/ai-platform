# Production bundle is prepared from pnpm-lock.yaml by scripts/build-images.sh.
FROM node:24.14.1-alpine
WORKDIR /app
COPY --chown=node:node runner/target/image/node_modules /app/node_modules
COPY --chown=node:node runner/target/image/package.json /app/package.json
COPY --chown=node:node runner/target/image/dist /app/dist
ARG VERSION=development
ARG REVISION=unknown
LABEL org.opencontainers.image.version=$VERSION org.opencontainers.image.revision=$REVISION ai.dsh.version="0.2.2-rc.1"
USER node
EXPOSE 8181 9091
STOPSIGNAL SIGTERM
CMD ["node", "dist/main.js"]
