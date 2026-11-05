# keystore/

放签名密钥的地方。规矩只有一条：**明文 `.jks` 永不进 git**（`.gitignore` 已挡）。

两种供钥方式，二选一（`build.sh` 与 GitHub Actions 按同一顺序找）：

1. **GitHub Secrets（推荐）**：仓库 Settings → Secrets and variables → Actions
   新建 `SIGNING_KEYSTORE_B64`，值为 `base64 -w0 ikanbot-tv.jks` 的输出；
   可选再加 `KEYSTORE_PASSWORD` / `KEY_PASSWORD` 覆盖默认口令。
2. **私密仓库内的 base64 文本**：把 `ikanbot-tv.jks.b64`
   （`base64 -w 76 ikanbot-tv.jks > ikanbot-tv.jks.b64`）放进本目录提交。
   仓库是私密的才可接受；将来仓库一旦转公开或加协作者，先删此文件改走 Secrets。

本地构建：把明文 `ikanbot-tv.jks` 放进本目录即可（被忽略，不会误提交）。
