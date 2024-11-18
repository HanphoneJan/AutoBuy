import time
import datetime
import requests
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium import webdriver
from selenium.webdriver.common.by import By  # 加载所需的库


# 首先我们需要设置抢购的时间，格式要按照预设的格式改就可以，个月数的一定在前面加上0，例如 “01”
now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
mstime = "2024-11-18 12:00:00.000000"
#print(mstime)
mstime = input("请输入时间: ")

# 选择使用的浏览器，如果没有Chrome浏览器可以更改其他浏览器，需要driver
# 反自动化脚本检测
options = webdriver.ChromeOptions()
options.add_experimental_option("excludeSwitches", ['enable-automation'])
# 取消“Chrome正受到自动测试软件的控制”和“请停用以开发者模式运行的扩展程序”
options.add_argument("--disable-blink-features=AutomationControlled")
#options.add_argument("--headless")
#options.add_argument("--disable-gpu")
options.add_argument("--no-sandbox")
options.add_argument("--incognito")
user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0 Safari/537.36"
options.add_argument(f'user-agent={user_agent}')
WebBrowser = webdriver.Chrome(options=options)

# 获取网站
WebBrowser.get("https://www.taobao.com")
#time.sleep(2)

# 进入网站后读取登录链接，并扫码登录
WebBrowser.find_element("link text", "亲，请登录").click()
# 京东：WebBrowser.find_element("link text","你好，请登录").click()
print(f"请扫码登录，15秒后将跳转")
time.sleep(15)

# 登录后直接转跳到购物车页面
WebBrowser.get("https://cart.taobao.com/cart.htm")
print(f"请选购，20秒后自动开始结算")
time.sleep(20)

#自动结算
while True:
    try:
        if WebBrowser.find_element(By.CLASS_NAME, "btn--QDjHtErD"):
            WebBrowser.find_element(By.CLASS_NAME, "btn--QDjHtErD").click()
            print(f"结算成功")
            break
        # 识别界面中的“结算”按钮并点击
    except:
        print(f"结算失败")
        break

time.sleep(15)
print(f"测试页面刷新时间")
num_tests = 3
total_load_time = 0
for i in range(num_tests):
    start_time = time.time()
    WebBrowser.refresh()
    WebDriverWait(WebBrowser, 2).until(EC.presence_of_element_located((By.CLASS_NAME, 'btn--QDjHtErD')))
    end_time = time.time()
    load_time = end_time - start_time
    total_load_time += load_time
    print(f'第{i+1}次加载时间：{load_time}秒')
    time.sleep(15)
average_load_time = total_load_time / num_tests
print(f'平均加载时间：{average_load_time}秒')
if average_load_time < 0.5:
    average_load_time = 0.5
#预留刷新页面的时间
mstime_datetime = datetime.datetime.strptime(mstime, "%Y-%m-%d %H:%M:%S.%f")
mstime_datetime = mstime_datetime - datetime.timedelta(seconds=average_load_time-0.1)
mstime = mstime_datetime.strftime('%Y-%m-%d %H:%M:%S.%f')

"根据淘宝时间校准本地时间"
#从淘宝服务器获取时间戳
def tb_time():
    pass
#获取本地时间戳
def local_time():
    local_timestamp = round(time.time() * 1000)
    return local_timestamp

#计算本地与淘宝服务器时间差（毫秒）
def local_tb_time_diff():
    tb_ts = tb_time()
    local_ts = local_time()
    return local_ts - tb_ts
'''
diff = local_tb_time_diff()
print("时间差（毫秒）：", diff)
if diff > 1000 or diff < -1000:
    mstime_datetime = mstime_datetime - datetime.timedelta(milliseconds=diff-100)
    mstime = mstime_datetime.strftime('%Y-%m-%d %H:%M:%S.%f')
    print(mstime)
'''
print(f"到达预定时间将自动开始抢购")

i = 0
while True:
    now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
    #print(now)
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
                break
            except:
                if i<10 :
                    i=i+1
                    time.sleep(1)
                    print(f"抢购失败，正在重新尝试")
                else:
                    print(f"抢购失败，20秒后自动关闭程序")
                    break
        break
time.sleep(20)
exit()
