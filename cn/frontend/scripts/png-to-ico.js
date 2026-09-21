const fs = require('fs');
const path = require('path');

const pngPath = path.resolve(__dirname, '../public/logo.png');
const icoPath = path.resolve(__dirname, '../public/favicon.ico');

if (!fs.existsSync(pngPath)) {
  console.error('logo.png not found at', pngPath);
  process.exit(1);
}

const pngData = fs.readFileSync(pngPath);

// ICO header: Reserved(2) + Type(1=ICON, 2) + Count(1, 2)
const header = Buffer.alloc(6);
header.writeUInt16LE(0, 0); // Reserved
header.writeUInt16LE(1, 2); // Type: ICON
header.writeUInt16LE(1, 4); // Count: 1 image

// Image directory entry (16 bytes)
const entry = Buffer.alloc(16);
entry.writeUInt8(0, 0);   // Width (0 means 256)
entry.writeUInt8(0, 1);   // Height (0 means 256)
entry.writeUInt8(0, 2);   // Colors (0 = >256)
entry.writeUInt8(0, 3);   // Reserved
entry.writeUInt16LE(1, 4);  // Color planes
entry.writeUInt16LE(32, 6); // Bits per pixel
entry.writeUInt32LE(pngData.length, 8); // Size of image data
entry.writeUInt32LE(22, 12); // Offset to image data (6 + 16)

const icoData = Buffer.concat([header, entry, pngData]);
fs.writeFileSync(icoPath, icoData);
console.log('Generated favicon.ico at', icoPath);
