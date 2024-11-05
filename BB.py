import time
import datetime
import requests
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium import webdriver
from selenium.webdriver.common.by import By  # 加载所需的库


# 选择使用的浏览器，如果没有Chrome浏览器可以更改其他浏览器，需要driver
# 反自动化脚本检测
options = webdriver.ChromeOptions()
#options.add_argument("--headless")
options.add_experimental_option("excludeSwitches", ['enable-automation'])
# 取消“Chrome正受到自动测试软件的控制”和“请停用以开发者模式运行的扩展程序”
options.add_argument('--disable-blink-features')
options.add_argument('--disable-blink-features=AutomationControlled')
# 去除浏览器selenium监控

options.add_argument("--disable-gpu")
options.add_argument("--no-sandbox")
options.add_argument("--incognito")
user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0 Safari/537.36"
options.add_argument(f'user-agent={user_agent}')
WebBrowser = webdriver.Chrome(options=options)

# 获取网站
WebBrowser.get("https://www.taobao.com")
time.sleep(2)

# 进入网站后读取登录链接，并扫码登录
WebBrowser.find_element("link text", "亲，请登录").click()
# 京东：WebBrowser.find_element("link text","你好，请登录").click()
print(f"请扫码登录，15秒后将跳转")
time.sleep(15)
WebBrowser.get("https://detail.tmall.com/item.htm?id=838484384474&pisk=fv4mmqcQwooXz0xoZgufjIQcO3s-lIgssRLtBVHN4Yk7MckOcRmgB8iakqFYEPVYVSLOcqniS8k7MFIjkzcaU8gt3tgAs12LCFeg1sGZQRyiBoIRJSNj5VWLIwQLGGUF0cwmQARP_fcH7-VTfFVj5VWdpwQLGSwEnOEsbflPZfhtgKyZbQlrFf0wuc824LkSUAuZ0m8y4XcBQE8q7_Ar_j8wQAk2ablIOFuZ7RPrCedrHRybzt9sNidUKw4mimDwWYYguz8KDvRiUFTsPjmDDSkk7Fzu2frf7xSBwAgxhSG0CwTjucqak4zVUNuUA84iY2jOG2zb_kZaNKfi37eb-qzhnEh-jxqzu0Aw7WPqE0qgntxj3oeoAboyswG8pYPbuuf6USyKnqlr2w53acr8lDaAUE0UAS3YbJWJDxrn_gr64HluNFGP6z-6fmlSZvpUdDcEuU98M_fkYkiqNj7dZ_x6fmlSZvClZHWI0bGVJ")


print(f"到达预定时间将自动开始抢购")

i = 0
while True:
    now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
    #print(now)
        # 当当前时间超过了抢购时间就立刻执行下面代码
    while True:
            try:
                WebBrowser.refresh()
                WebBrowser.find_element(By.CLASS_NAME,"btn--Jy7gBgTJ undefined").click()
                WebBrowser.find_element(By.CLASS_NAME, "btn--QDjHtErD").click()
                print(f"抢购成功，请尽快付款")
                now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
                print(now)
                print(f"20秒后自动关闭程序，请尽快付款")
                break
            except:
                i=i+1
                time.sleep(15)
                #print(f"抢购失败，正在重新尝试")
    break
time.sleep(20)
exit()
