const http = require("node:http");

const port = Number(process.env.PORT ?? 8080);

const server = http.createServer((request, response) => {
  if (request.url === "/healthz") {
    response.writeHead(200, { "content-type": "text/plain" }).end("ok\n");
    return;
  }
  response.writeHead(200, { "content-type": "text/plain" }).end("hello from pallet\n");
});

server.listen(port, () => console.log(`listening on ${port}`));

for (const signal of ["SIGTERM", "SIGINT"]) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
