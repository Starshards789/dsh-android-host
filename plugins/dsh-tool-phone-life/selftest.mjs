// phone-life 插件的真机自测（以 App 的 uid + SELinux context 运行）
const P = "/data/user/0/cc.bestly.dsh/files/dshhome/profiles/web";

async function main() {
    const m = await import(`${P}/node_modules/dsh-tool-phone-life/lib/index.js`);
    const caps = [];
    m.apply({ tools: { register: (t) => caps.push(t) } });
    console.log("工具:", caps.map((c) => c.name).join(", "));
    console.log("");

    for (const c of caps) {
        let args = {};
        if (c.name === "phone_file") args = { action: "list", path: "." };
        if (c.name === "phone_calendar") args = { action: "list", days: 7 };
        if (c.name === "phone_contacts") args = { limit: 5 };
        let r;
        try {
            r = await c.execute(args);
        } catch (e) {
            console.log(`${c.name}: 抛异常 ${e && e.message}`);
            continue;
        }
        console.log(`${c.name}  ok=${r.ok}  available=${r.available ?? "-"}`);
        console.log(`  ${(r.summary || r.error || "").slice(0, 200)}`);
        if (c.name === "phone_file" && r.entries) {
            console.log(`  条目: ${r.entries.map((e) => `${e.type === "dir" ? "📁" : "📄"}${e.name}`).join(" ") || "（空）"}`);
        }
        if (c.name === "phone_contacts" && r.contacts) {
            console.log(`  联系人数: ${r.contacts.length} / 总 ${r.total}`);
        }
        console.log("");
    }

    // 文件工具写读闭环
    const fileTool = caps.find((c) => c.name === "phone_file");
    if (fileTool) {
        console.log("=== 文件工具 write → read → search 闭环 ===");
        const w = await fileTool.execute({
            action: "write",
            path: "selftest/hello.txt",
            content: "零一自测文件\n第二行：这条是 phone_file 写入的\n第三行：用于验证 search\n",
        });
        console.log("write:", w.ok, "|", w.summary || w.error);
        const rd = await fileTool.execute({ action: "read", path: "selftest/hello.txt" });
        console.log("read :", rd.ok, "|", (rd.content || "").split("\n")[1] || rd.error);
        const se = await fileTool.execute({ action: "search", path: ".", pattern: "零一自测" });
        console.log("search:", se.ok, "|", se.summary || se.error);
        const ls = await fileTool.execute({ action: "list", path: "selftest" });
        console.log("list :", ls.ok, "|", ls.summary || ls.error);
        console.log("越界测试:", (await fileTool.execute({ action: "read", path: "../../../dshhome/.credentials.yaml" })).error);
    }
}

main().catch((e) => console.log("FAIL:", (e && e.message) || e));
