# GitHub HTTPS 推送修复

## 目的

为当前仓库设置一组仅作用于本仓库的 Git 配置，降低 GitHub HTTPS 推送在公司网络、代理链路或中间设备干扰下出现以下问题的概率：

- `error: RPC failed; HTTP 302 curl 22 The requested URL returned error: 302`
- `send-pack: unexpected disconnect while reading sideband packet`
- `curl 55 Send failure: Input/output error`
- 终端显示 `Everything up-to-date`，但远端分支实际上没有更新

## 适用范围

- 远端使用 `https://github.com/...` 地址
- 本机已经安装 Git for Windows
- 本机存在可用的本地代理，例如 Clash、V2RayN 或其他提供 SOCKS5 端口的代理工具
- 只希望影响当前仓库，不修改全局 Git 配置

## 目录内容

- `setup-git-proxy-and-push.ps1`：一键设置当前仓库的 GitHub HTTPS 推送配置，并可直接推送当前分支

## 脚本做了什么

脚本只写入当前仓库的 `.git/config`，不会修改全局配置。主要动作如下：

1. 设置 `http.sslBackend=schannel`，优先使用 Windows 证书库处理 TLS
2. 设置 `http.version=HTTP/1.1` 和 `http.expect=false`，降低链路兼容性问题
3. 设置 `http.maxRequests=1`、`core.compression=0`、`http.lowSpeedLimit=0`
4. 清理当前仓库中残留的通用 `http.proxy` 和 `https.proxy`
5. 仅为 `https://github.com` 设置仓库级 `socks5h` 代理
6. 推送前先执行 `git ls-remote` 做只读连通性检查
7. 推送异常时，再通过本地与远端分支哈希比对判断是否已经实际成功

## 推荐用法

在仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\docs\tools\github-push\setup-git-proxy-and-push.ps1 -NoPush -ProxyPort 7897
```

如果你的本地代理端口不是 `7897`，请改成实际端口，例如 `7890`：

```powershell
powershell -ExecutionPolicy Bypass -File .\docs\tools\github-push\setup-git-proxy-and-push.ps1 -NoPush -ProxyPort 7890
```

确认配置无误后，再执行推送：

```powershell
powershell -ExecutionPolicy Bypass -File .\docs\tools\github-push\setup-git-proxy-and-push.ps1 -ProxyPort 7897
```

## 常用参数

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `-ProxyScheme` | 代理协议，建议保留 `socks5h` | `socks5h` |
| `-ProxyHost` | 本地代理地址 | `127.0.0.1` |
| `-ProxyPort` | 本地代理端口 | `7897` |
| `-NoPush` | 只配置，不执行推送 | 无 |
| `-Remote` | 远端名称 | `origin` |
| `-Branch` | 推送分支，不传时自动读取当前分支 | 当前分支 |
| `-MaxPushRetries` | 推送重试次数 | `3` |
| `-MaxCheckRetries` | 连通性检查重试次数 | `3` |

## 执行后如何确认是否真的推送成功

不要只看 `git push` 的文字提示，建议再执行以下命令：

```powershell
git rev-parse HEAD
git ls-remote origin refs/heads/main
```

如果两条命令输出的提交哈希一致，说明远端分支已经更新。

如果你当前不在 `main` 分支，请把 `refs/heads/main` 改成对应分支名。

## 手动最小配置

如果不想执行脚本，也可以在仓库根目录手动设置：

```powershell
git config --local http.sslBackend schannel
git config --local http.version HTTP/1.1
git config --local http.expect false
git config --local http.sslVerify true
git config --local http.maxRequests 1
git config --local core.compression 0
git config --local http.lowSpeedLimit 0
git config --local http.postBuffer 524288000

git config --local --unset-all http.proxy 2>$null
git config --local --unset-all https.proxy 2>$null
git config --local --unset http.https://github.com.proxy 2>$null
git config --local http.https://github.com.proxy socks5h://127.0.0.1:7897
```

## 回滚当前仓库配置

如果后续不再需要这组配置，可在仓库根目录执行：

```powershell
git config --local --unset http.sslBackend
git config --local --unset http.version
git config --local --unset http.expect
git config --local --unset http.sslVerify
git config --local --unset http.maxRequests
git config --local --unset core.compression
git config --local --unset http.lowSpeedLimit
git config --local --unset http.postBuffer
git config --local --unset http.https://github.com.proxy
```

## 说明

- `socks5h` 会让域名解析交给代理端完成，通常比普通 HTTP 代理更稳定
- 如果脚本执行后仍然推送失败，优先检查本地代理是否可用、端口是否正确，以及代理工具是否允许 Git 进程联网
- 如果 HTTPS 链路长期不稳定，可以再考虑改成 SSH 推送
