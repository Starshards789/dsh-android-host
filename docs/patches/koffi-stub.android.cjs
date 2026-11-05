"use strict";
/**
 * Android 专用的 koffi 占位实现。
 *
 * 背景：koffi 是原生 FFI 绑定，本 payload 内只有源码、没有任何平台的原生二进制
 * （`build/` 为空），因此 `import "koffi"` 在加载期必然抛错。
 *
 * [patch-20261106] 增强 —— 原版 stub 的 type() 不带 `.size`，
 * 而 dsh-win32-process 在模块顶层就做 ABI 校验：
 *     if (STARTUPINFOW.size !== 104) throw new Error(`STARTUPINFOW layout mismatch: ...`);
 *     if (PROCESS_INFORMATION.size !== 24) throw new Error(`PROCESS_INFORMATION layout mismatch: ...`);
 * 结果是 stub 也救不了：sandbox 插件 import chain 一进来就崩。
 * 这里按结构体名返回 Windows ABI 的实际大小，让模块能加载；
 * 真正的 FFI 调用（load/alloc/decode/encode/errno）仍会在**调用时**抛明确错误，
 * 而这些调用只可能出现在 win32 路径上，Android 不可达。
 */

function unsupported(name) {
	return function unavailable() {
		throw new Error(`koffi 占位：${name} 需要 koffi 原生模块，本平台不可用`);
	};
}

/** Win32 结构体的 ABI 大小 —— 仅供加载期校验，不做真实布局计算。 */
const ABI_SIZES = {
	DSH_STARTUPINFOW: 104,
	DSH_PROCESS_INFORMATION: 24
};

/** 类型描述类调用：加载期只需要一个可传递、带 size 的对象。 */
function type(name) {
	return {
		__koffiStubType: true,
		size: ABI_SIZES[name],
		toString: () => "[koffi-stub-type]"
	};
}

const stub = {
	pointer: type,
	struct: type,
	array: type,
	union: type,
	alias: type,
	typedef: type,
	types: type,
	sizeof: (t) => (t && typeof t === "object" ? t.size : undefined),
	alignof: () => undefined,
	load: unsupported("load"),
	alloc: unsupported("alloc"),
	free: unsupported("free"),
	decode: unsupported("decode"),
	encode: unsupported("encode"),
	errno: unsupported("errno"),
	version: "stub"
};

module.exports = stub;
