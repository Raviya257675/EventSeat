// Local test gateway only. Production gateway always defaults to Java port 5000.
import { createGateway } from '../gateway/server.mjs';
const gateway = createGateway({tcpPort:5001});
gateway.server.listen(8081,'127.0.0.1',() => console.log('Isolated test gateway: http://127.0.0.1:8081'));
for (const signal of ['SIGINT','SIGTERM']) process.on(signal,() => gateway.close().then(() => process.exit()));
