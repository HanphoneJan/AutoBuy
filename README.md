# 淘宝京东抢购工具

项目开源地址：https://github.com/HanphoneJan/AutoBuy.git

项目更新时间：2024.11.18

支持windows网页抢购京东或淘宝商品，仅支持能够添加到购物车并进入提交订单页面的商品

可以自动提交订单，但不能自动付款

京东移动端网址：https://m.jd.com/
淘宝移动端网址：https://main.m.taobao.com/

## 我的开发环境配置

windows11/10，pycharm(professional)，python3.12

chrome,chromedriver 

库：selenium  
可以使用命令行安装库：pip install selenium
[edge浏览器驱动下载](https://developer.microsoft.com/en-us/microsoft-edge/tools/webdriver/?form=MA13LH)
[谷歌浏览器驱动下载](https://googlechromelabs.github.io/chrome-for-testing/)

## 代码打包

命令行输入

```
pip install pyinstaller
无图标打包：pyinstaller -F TB.py  
有图标打包：pyinstaller -F -i 图标路径 TB.py
```

## 使用教程
首先请下载最新版谷歌浏览器  [下载链接](https://zh-googe.com/)
请提前五分钟启动程序，程序运行时请勿关闭窗口

### 淘宝抢购
打开淘宝抢购.exe文件
1. 输入抢购时间
   格式: YYYY-MM-DD HH:MM:SS.ffffff
   例子：2024-01-01 01:34:00.000000
   请尽量严格按照格式输入，个月数的一定在前面加上0，例如 “01”
2. 程序将打开谷歌浏览器进入网页，并自动进入登录页面，请尽快扫码登录，15秒后将跳转购物车
3. 在购物车界面自行选中商品，20秒后自动开始结算
4. 到达预定时间将自动提交订单
5. 尽快在网页中输入密码付款
网页刷新过程中如遇到滑块验证等情况请手动解决
### 京东抢购
打开京东抢购.exe文件

1. 输入抢购时间
   格式: YYYY-MM-DD HH:MM:SS.ffffff
   例子：2024-01-01 01:34:00.000000
   请尽量严格按照格式输入，个月数的一定在前面加上0，例如 “01”
2. 程序将打开谷歌浏览器进入网页，并自动进入扫登录页面，请尽快扫码登录
3. 由于京东购物车页面有反爬虫机制，请在另一个浏览器打开京东选好商品后进入提交订单页面（这步建议提前做好）
4. .将提交订单页面的网址复制到程序打开的浏览器地址栏中打开
5. 到达预定时间将自动提交订单