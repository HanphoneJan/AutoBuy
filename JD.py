import datetime
import time
from selenium import webdriver
from selenium.webdriver.common.by import By  # 加载所需的库

# 首先我们需要设置抢购的时间，格式要按照预设的格式改就可以，个月数的一定在前面加上0，例如 “01”
now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
mstime = "2024-10-30 19:49:00.000000"
#print(mstime)
#mstime = input("请输入时间: ")

# 选择使用的浏览器，如果没有Chrome浏览器可以更改其他浏览器，需要相应的driver
WebBrowser = webdriver.Chrome()

# 获取网站
WebBrowser.get("https://www.jd.com")
time.sleep(2)

# 进入网站后读取登录链接，并扫码登录
WebBrowser.find_element("link text","你好，请登录").click()
print(f"请扫码登录")
time.sleep(25)


# 登录后直接转跳到购物车页面，京东购物车页面被反爬虫了
#WebBrowser.find_element(By.LINK_TEXT, '购物车').click() 可以
#WebBrowser.get("https://cart.jd.com/cart_index")  可以
#WebBrowser.find_element(By.XPATH, '//svg/use[@xlink:href="#icon_cart"]') 不可以

# 直接跳转到提交订单界面
#WebBrowser.get("https://trade.jd.com/shopping/order/getOrderInfo.action")

#print(f"请选购，20秒后自动开始结算")
print(f"一分钟后将开始尝试提交订单")
time.sleep(60)

'''
#自动结算
while True:
    try:
        if WebBrowser.find_element(By.CLASS_NAME, "common-submit-btn font-semibold"):
            WebBrowser.find_element(By.CLASS_NAME, "common-submit-btn font-semibold").click()
            # 京东：if WebBrowser.find_element("link text", "去结算"):
            #  京东：WebBrowser.find_element("link text", "去结算").click()
            print(f"结算成功")
            break
        # 识别界面中的“结算”按钮并点击
    except:
        print(f"结算失败")
        break

print(f"到达预定时间将自动开始抢购")
'''

while True:
    now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
    #print(now)
    if now >= mstime:
        # 当当前时间超过了抢购时间就立刻执行下面代码
        while True:
            try:
                if WebBrowser.find_element(By.CLASS_NAME, "checkout-submit"):
                    WebBrowser.find_element(By.CLASS_NAME,"checkout-submit").click()
                    print(f"抢购成功，请尽快付款")
                    print(now)
                    break
            except:
                print(f"抢购失败")
                break
        time.sleep(0.01)