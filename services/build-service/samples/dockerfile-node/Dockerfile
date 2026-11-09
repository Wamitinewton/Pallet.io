FROM docker.io/library/node:26.13.0-alpine3.24@sha256:8996bb5821d62a8fd9bb27c1cb07462f3fdce6e47d640c6a881ead0b064ce216

ENV NODE_ENV=production PORT=8080
WORKDIR /app
COPY package.json server.js ./
RUN node --check server.js

USER node
EXPOSE 8080
CMD ["node", "server.js"]
