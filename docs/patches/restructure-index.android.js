// [patch-android] 官方 payload 漏打包 src/ 目录（index.js 引用的 14 个源码文件全不在），
// 导致 ESM import 直接 ERR_MODULE_NOT_FOUND。dist/main.cjs 是 parcel 打好的完整实现，
// 导出面比 index.js 还全（51 个 vs 17 个），这里改成从它整体重新导出。
import main from './dist/main.cjs';

export default main;

export const {
  Array, Bitfield, Boolean, Buffer, DecodeStream, EncodeStream, Enum, Fixed,
  LazyArray, Number, Optional, Pointer, PropertyDescriptor, Reserved, String,
  Struct, VersionedStruct, VoidPointer,
  double, doublebe, doublele,
  fixed16, fixed16be, fixed16le, fixed32, fixed32be, fixed32le,
  float, floatbe, floatle,
  int8, int16, int16be, int16le, int24, int24be, int24le,
  int32, int32be, int32le,
  uint8, uint16, uint16be, uint16le, uint24, uint24be, uint24le,
  uint32, uint32be, uint32le,
  resolveLength
} = main;
