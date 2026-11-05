// NagomiAni「一起看」中继（relay）：按房间码把 JSON 消息原样转发给同房间的其他成员。
//
// 设计边界（刻意保持最小）：
// - 无业务逻辑：不解析播放状态、不存历史、不持久化；最后一名成员离开即销毁房间
// - 房主 = 首位成员；离开时按加入顺位交接；旧房主同昵称短期内重进可收回房主位
// - 限额：每房间 16 人、单条消息 8KB，超限断开（防滥用）
//
// 协议：ws://host:port/room/<ROOMCODE>
//   客户端 → relay：入房首条 {"t":"hello","nick":...}；
//   此后 {"t":"state"|"chat"|"sync"|"bye"} 原样广播给其他人（附 from 标记）；
//   {"t":"ping"} 单播回 {"t":"pong"}（应用层 RTT 测量）。
//   relay → 客户端：{"t":"welcome","you":id,"host":id,"members":[...]}、
//   {"t":"members","host":id,"members":[...]}（成员/房主变化）。
//
// 运行：npm install && PORT=8787 node index.js

const http = require('http');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT || 8787);
const MAX_MEMBERS_PER_ROOM = 16;
const HOST_RECLAIM_TTL_MS = 10 * 60 * 1000; // 房主离开后，同昵称 10 分钟内重进可收回房主位

/** roomCode -> { members: Map<id, {socket, nick}>, host: id|null, lastHostNick, lastHostAt } */
const rooms = new Map();

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
  res.end('nagomiani watchparty relay\n');
});

const wss = new WebSocketServer({ server, maxPayload: 8 * 1024 });

function codeOf(request) {
  const m = /^\/room\/([A-Za-z0-9]{4,16})$/.exec(new URL(request.url, 'http://relay').pathname);
  return m ? m[1].toUpperCase() : null;
}

function send(socket, obj) {
  if (socket.readyState === 1 /* OPEN */) socket.send(JSON.stringify(obj));
}

/** 广播给房间除 exceptId 外的所有成员（exceptId=null 即全员） */
function broadcast(code, exceptId, obj) {
  const room = rooms.get(code);
  if (!room) return;
  for (const [id, member] of room.members) {
    if (id !== exceptId) send(member.socket, obj);
  }
}

function membersPayload(room) {
  return [...room.members.entries()].map(([id, m]) => ({ id, nick: m.nick }));
}

function join(code, id, socket, nick) {
  let room = rooms.get(code);
  if (!room) {
    room = { members: new Map(), host: null, lastHostNick: null, lastHostAt: 0 };
    rooms.set(code, room);
  }
  if (!room.members.has(id) && room.members.size >= MAX_MEMBERS_PER_ROOM) {
    send(socket, { t: 'full' });
    socket.close(4002, 'room full');
    return;
  }
  // 房主位规则：空房即房主；近期离开的旧房主以同昵称重进则收回房主位
  const reclaim = room.host === null && room.lastHostNick === nick &&
    Date.now() - room.lastHostAt < HOST_RECLAIM_TTL_MS;
  room.members.set(id, { socket, nick });
  if (room.host === null) {
    room.host = id;
    if (reclaim) room.lastHostNick = null;
  }
  send(socket, { t: 'welcome', you: id, host: room.host, members: membersPayload(room) });
  broadcast(code, id, { t: 'members', host: room.host, members: membersPayload(room) });
}

function leave(code, id) {
  const room = rooms.get(code);
  if (!room || !room.members.has(id)) return;
  const wasHost = room.host === id;
  const nick = room.members.get(id).nick;
  room.members.delete(id);
  if (room.members.size === 0) {
    if (wasHost) {
      room.lastHostNick = nick;
      room.lastHostAt = Date.now();
    }
    rooms.delete(code);
    return;
  }
  if (wasHost) {
    // 房主掉线：记住昵称供收回，房主位按加入顺位交接
    room.lastHostNick = nick;
    room.lastHostAt = Date.now();
    room.host = room.members.keys().next().value;
  }
  broadcast(code, null, { t: 'members', host: room.host, members: membersPayload(room) });
}

wss.on('connection', (socket, request) => {
  const code = codeOf(request);
  if (!code) {
    socket.close(4001, 'bad room path');
    return;
  }
  const id = crypto.randomBytes(4).toString('hex'); // 8 位短 id，客户端用于判断自己/房主
  let joined = false;
  socket.isAlive = true;

  socket.on('message', (data, isBinary) => {
    if (isBinary) return;
    let msg;
    try {
      msg = JSON.parse(data.toString('utf8'));
    } catch {
      return;
    }
    if (!msg || typeof msg.t !== 'string') return;

    if (!joined) {
      if (msg.t !== 'hello') return;
      joined = true;
      join(code, id, socket, String(msg.nick || '路人').slice(0, 24));
      return;
    }

    switch (msg.t) {
      case 'ping':
        send(socket, { t: 'pong', at: Number(msg.at) || 0 });
        return;
      case 'state':
      case 'chat':
      case 'sync':
      case 'bye':
        broadcast(code, id, { ...msg, from: id });
        return;
      default:
        return; // 其余类型不接受（hello 重复/未知类型），防滥用
    }
  });

  socket.on('close', () => leave(code, id));
  socket.on('error', () => leave(code, id));
});

// 死连接清扫：每 30s 一轮协议层 ping，连续两轮无响应即断开（触发客户端自动重连）
setInterval(() => {
  for (const room of rooms.values()) {
    for (const member of room.members.values()) {
      if (member.socket.isAlive === false) {
        member.socket.terminate();
        continue;
      }
      member.socket.isAlive = false;
      member.socket.ping();
    }
  }
}, 30_000).unref();

server.listen(PORT, () => {
  console.log(`[nagomiani-watchparty] relay listening on :${PORT}`);
});
