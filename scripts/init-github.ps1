# 首次上传到 GitHub 前运行此脚本（需已安装 Git for Windows 并登录 GitHub）
# 用法：
#   cd 项目根目录
#   .\scripts\init-github.ps1 -RepoUrl "https://github.com/你的用户名/仓库名.git"

param(
    [Parameter(Mandatory = $true)]
    [string]$RepoUrl
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 git。请先安装 Git for Windows: https://git-scm.com/download/win"
}

if (-not (Test-Path ".git")) {
    git init
    git branch -M main
}

git add -A
git status

$msg = "Initial commit: yuhuo virtual microphone (GlassMic fork)"
git commit -m $msg

if (-not (git remote | Select-String -Pattern "^origin$" -Quiet)) {
    git remote add origin $RepoUrl
} else {
    git remote set-url origin $RepoUrl
}

Write-Host ""
Write-Host "即将推送到: $RepoUrl"
Write-Host "请先在 GitHub 网页上创建空仓库（不要勾选 README / .gitignore）"
Write-Host ""
git push -u origin main
