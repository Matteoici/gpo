"""Ping de statut Minecraft (comme la liste des serveurs) : vérifie que le port du jeu répond toujours normalement."""
import socket, struct, json, sys

def varint(n):
    out = b''
    while True:
        b = n & 0x7F; n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n: return out

def read_varint(s):
    n = shift = 0
    while True:
        b = s.recv(1)
        if not b: raise EOFError('connexion fermée')
        n |= (b[0] & 0x7F) << shift; shift += 7
        if not b[0] & 0x80: return n

host, port = sys.argv[1], int(sys.argv[2])
s = socket.create_connection((host, port), timeout=10)
h = varint(0) + varint(-1 & 0xFFFFFFFF) + varint(len(host)) + host.encode() + struct.pack('>H', port) + varint(1)
s.sendall(varint(len(h)) + h)
s.sendall(varint(1) + b'\x00')
read_varint(s); read_varint(s); ln = read_varint(s)
data = b''
while len(data) < ln:
    c = s.recv(ln - len(data))
    if not c: break
    data += c
st = json.loads(data)
print('ping OK :', st['version']['name'], '-', st['players'])
