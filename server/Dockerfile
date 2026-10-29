FROM node:24-slim

ENV NODE_ENV=production \
    DATA_DIR=/data \
    PORT=3090

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund && npm cache clean --force

COPY src ./src
COPY public ./public

RUN mkdir -p /data && chown node:node /data
USER node
VOLUME /data
EXPOSE 3090

HEALTHCHECK --interval=60s --timeout=5s \
  CMD node -e "fetch('http://localhost:3090/api/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "--disable-warning=ExperimentalWarning", "src/index.js"]
