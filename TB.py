import time
import datetime
from selenium import webdriver
from selenium.webdriver.common.by import By  # 加载所需的库

# 首先我们需要设置抢购的时间，格式要按照预设的格式改就可以，个月数的一定在前面加上0，例如 “01”
now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
mstime = "2024-10-31 20:00:00.000000"
#print(mstime)
mstime = input("请输入时间: ")

#预留刷新页面的时间
mstime_datetime = datetime.datetime.strptime(mstime, "%Y-%m-%d %H:%M:%S.%f")
mstime_datetime = mstime_datetime - datetime.timedelta(seconds=0.5)
mstime = mstime_datetime.strftime('%Y-%m-%d %H:%M:%S.%f')

# 选择使用的浏览器，如果没有Chrome浏览器可以更改其他浏览器，需要driver
WebBrowser = webdriver.Chrome()

# 获取网站
WebBrowser.get("https://www.taobao.com")
# 京东：WebBrowser.get("https://www.jd.com")
time.sleep(2)

# 进入网站后读取登录链接，并扫码登录
WebBrowser.find_element("link text", "亲，请登录").click()
# 京东：WebBrowser.find_element("link text","你好，请登录").click()
print(f"请扫码登录，15秒后将跳转")
time.sleep(15)

# 登录后直接转跳到购物车页面
WebBrowser.get("https://cart.taobao.com/cart.htm")
print(f"请选购，20秒后自动开始结算")
time.sleep(20)
# 京东：WebBrowser.get("https://cart.jd.com/cart_index")

#自动结算
while True:
    try:
        if WebBrowser.find_element(By.CLASS_NAME, "btn--QDjHtErD"):
            WebBrowser.find_element(By.CLASS_NAME, "btn--QDjHtErD").click()
            # 京东：if WebBrowser.find_element("link text", "去结算"):
            #  京东：WebBrowser.find_element("link text", "去结算").click()
            print(f"结算成功")
            break
        # 识别界面中的“结算”按钮并点击
    except:
        print(f"结算失败")
        break

print(f"到达预定时间将自动开始抢购")

while True:
    now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
    print(now)
    if now >= mstime:
        # 当当前时间超过了抢购时间就立刻执行下面代码
        while True:
            try:
                WebBrowser.refresh()
                WebBrowser.find_element(By.CLASS_NAME,"btn--QDjHtErD").click()
                print(f"抢购成功，请尽快付款")
                now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
                print(now)
                print(f"20秒后自动关闭程序，请尽快付款")
                time.sleep(20)
                break
            except:
                print(f"抢购失败")
                time.sleep(20)
                break
        time.sleep(0.001)
        break
exit()