# NagomiAni「一起看」中继（relay）

按房间码转发 WebSocket 消息的最小中继服务，**无业务逻辑、无持久化**：
房主客户端广播播放状态，同房间成员原样收到；最后一人离开即销毁房间。

## 运行

```bash
cd server
npm install
PORT=8787 node index.js   # 或 npm start（默认 8787）
```

健康检查：`curl http://127.0.0.1:8787/` 返回 `nagomiani watchparty relay`。

## 接入 App

播放器竖屏面板 →「一起看」→ 服务器地址填 `ws://<本机IP>:8787`（或部署后的
`wss://` 域名），一人「创建房间」拿到 6 位房间码，朋友「加入房间」输码即可。

想把地址做成 App 默认值：改 `RoomClient` 里的 `DEFAULT_RELAY_URL` 常量后自行打包。

## 部署建议

- 任意一台有公网 IP 的 VPS + Caddy/Nginx 做 TLS 反代（App 端填 `wss://`）。
- 限额：每房间 16 人、单条消息 8KB、30s 死连接清扫——小圈子自用足够，
  公网暴露请注意自行加鉴权/限流。
- 协议细节见 `index.js` 头部注释；消息模型与
  `app/.../core/watchparty/WatchPartyMessages.kt` 一一对应。
