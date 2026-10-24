import unittest
from unittest.mock import patch

from app import run_seckill_task, task_manager
from seckill import SeckillWorker


class FakeElement:
    def __init__(self, name):
        self.name = name

    def is_displayed(self):
        return True

    def get_attribute(self, name):
        return None


class FakeTaobaoDriver:
    def __init__(self):
        self.current_url = "https://buy.tmall.com/order/confirm_order.htm"


class FakeJdDriver:
    def __init__(self):
        self.current_url = "https://cart.jd.com/cart_index"
        self.refresh_count = 0
        self.selected = False
        self.checkbox = FakeElement("checkbox")
        self.checkout = FakeElement("checkout")
        self.submit = FakeElement("submit")

    def refresh(self):
        self.refresh_count += 1

    def get(self, url):
        self.current_url = url

    def execute_script(self, script, *args):
        if "document.body ? document.body.innerText" in script:
            return "目标商品 标准套装"
        if "const keyword" in script:
            return self.checkbox if self.refresh_count >= 2 else None
        if "aria-checked" in script:
            return {"disabled": False, "selected": self.selected}
        if "const nodes = Array.from" in script and "去结算" in script:
            return self.checkout if self.selected else None
        return "complete"

    def find_elements(self, by, value):
        if "checkout-submit" in value and "getOrderInfo" in self.current_url:
            return [self.submit]
        return []


class FakeTaobaoCartDriver:
    """淘宝购物车：首次定位失败，刷新后才出现可勾选的复选框。"""

    def __init__(self):
        self.current_url = "https://cart.taobao.com/cart.htm"
        self.refresh_count = 0
        self.selected = False
        self.checkbox = FakeElement("checkbox")
        self.checkout = FakeElement("checkout")
        self.submit = FakeElement("submit")

    def refresh(self):
        self.refresh_count += 1

    def get(self, url):
        self.current_url = url

    def execute_script(self, script, *args):
        if "document.body ? document.body.innerText" in script:
            return ""
        if "const keyword" in script:
            return self.checkbox if self.refresh_count >= 1 else None
        if "aria-checked" in script:
            return {"disabled": False, "selected": self.selected}
        if "去结算" in script:
            return self.checkout if self.selected else None
        return None

    def find_elements(self, by, value):
        return []


class SeckillFlowTests(unittest.TestCase):
    def test_confirm_order_url_is_not_success_by_itself(self):
        worker = SeckillWorker("tb")
        worker.driver = FakeTaobaoDriver()
        self.assertFalse(worker._verify_order_submitted())

    def test_taobao_confirm_page_clicks_submit_and_reaches_payment(self):
        worker = SeckillWorker("tb")
        driver = FakeTaobaoDriver()
        worker.driver = driver
        worker.running = True
        submit = FakeElement("submit")
        worker._find_action_button = lambda action: submit if action == "submit" else None

        def click(element):
            if element is submit:
                driver.current_url = "https://cashier.alipay.com/payment"
            return True

        worker._click_element_safely = click
        self.assertTrue(worker._perform_tb_seckill(max_wait_seconds=1))

    def test_jd_refreshes_before_selecting_and_checkout(self):
        worker = SeckillWorker("jd", product_keyword="目标商品")
        driver = FakeJdDriver()
        worker.driver = driver
        worker.running = True
        worker._wait_for_document_ready = lambda timeout=5.0: None
        worker._find_action_button = (
            lambda action: driver.submit
            if action == "submit" and "getOrderInfo" in driver.current_url
            else None
        )

        def click(element):
            if element is driver.checkbox:
                driver.selected = True
            elif element is driver.checkout:
                driver.current_url = (
                    "https://trade.jd.com/shopping/order/getOrderInfo.action"
                )
            elif element is driver.submit:
                driver.current_url = "https://cashier.jd.com/pay"
            return True

        worker._click_element_safely = click
        self.assertTrue(worker._perform_jd_seckill())
        self.assertGreaterEqual(driver.refresh_count, 2)

    def test_jd_without_keyword_falls_back_to_first_item(self):
        worker = SeckillWorker("jd")
        driver = FakeJdDriver()
        worker.driver = driver
        worker.running = True
        worker._wait_for_document_ready = lambda timeout=5.0: None
        worker._find_action_button = (
            lambda action: driver.submit
            if action == "submit" and "getOrderInfo" in driver.current_url
            else None
        )

        def click(element):
            if element is driver.checkbox:
                driver.selected = True
            elif element is driver.checkout:
                driver.current_url = (
                    "https://trade.jd.com/shopping/order/getOrderInfo.action"
                )
            elif element is driver.submit:
                driver.current_url = "https://cashier.jd.com/pay"
            return True

        worker._click_element_safely = click
        self.assertTrue(worker._perform_jd_seckill())

    def test_taobao_refreshes_cart_and_auto_checks_item(self):
        worker = SeckillWorker("tb", product_keyword="目标商品")
        driver = FakeTaobaoCartDriver()
        worker.driver = driver
        worker.running = True
        worker._wait_for_document_ready = lambda timeout=5.0: None
        worker._find_action_button = lambda action: (
            driver.submit if action == "submit"
            else driver.checkout if action == "checkout"
            else None
        )

        def click(element):
            if element is driver.checkbox:
                driver.selected = True
            elif element is driver.checkout:
                driver.current_url = "https://buy.taobao.com/auction/confirm_order.htm"
            elif element is driver.submit:
                driver.current_url = "https://cashier.alipay.com/payment"
            return True

        worker._click_element_safely = click
        self.assertTrue(worker._perform_tb_seckill(max_wait_seconds=2))
        self.assertGreaterEqual(driver.refresh_count, 1)
        self.assertTrue(driver.selected)

    def test_taobao_with_keyword_skips_checkout_when_item_missing(self):
        worker = SeckillWorker("tb", product_keyword="目标商品")
        driver = FakeTaobaoCartDriver()
        worker.driver = driver
        worker.running = True
        worker._find_cart_checkbox = lambda: None
        worker._refresh_cart_page = lambda: None
        actions = []

        def find_action(action):
            actions.append(action)
            return driver.checkout if action == "checkout" else None

        worker._find_action_button = find_action
        worker._click_element_safely = lambda element: True
        worker._perform_tb_seckill(max_wait_seconds=0.5)
        self.assertNotIn("checkout", actions)

    def test_taobao_without_keyword_keeps_manual_checkout_fallback(self):
        worker = SeckillWorker("tb")
        driver = FakeTaobaoCartDriver()
        worker.driver = driver
        worker.running = True
        worker._find_cart_checkbox = lambda: None
        worker._refresh_cart_page = lambda: None
        clicked = []

        def find_action(action):
            return driver.checkout if action == "checkout" else None

        def click(element):
            clicked.append(element)
            if element is driver.checkout:
                driver.current_url = "https://cashier.alipay.com/payment"
            return True

        worker._find_action_button = find_action
        worker._click_element_safely = click
        worker._perform_tb_seckill(max_wait_seconds=0.5)
        self.assertIn(driver.checkout, clicked)

    def test_auto_check_retries_until_selected(self):
        worker = SeckillWorker("tb", product_keyword="目标商品")
        driver = FakeTaobaoCartDriver()
        driver.refresh_count = 1  # 复选框已出现，跳过刷新
        worker.driver = driver

        clicks = []

        def click(element):
            clicks.append(element)
            if len(clicks) >= 2:  # 第一次点击不生效，验证会自动再点一次
                driver.selected = True
            return True

        worker._click_element_safely = click
        checked, reason = worker._auto_check_cart_item(retries=3)
        self.assertTrue(checked)
        self.assertEqual(reason, "checked")
        self.assertEqual(len(clicks), 2)

    def test_purchase_limit_is_reported_as_failure(self):
        worker = SeckillWorker("tb")
        self.assertEqual(
            worker._find_failure_keyword("该商品购买超出限制，自动没了"),
            "购买超出限制",
        )

    def test_task_status_reflects_worker_result(self):
        task_id = task_manager.create_task("tb", "2026-06-23 20:00:00")

        class FailedWorker:
            def __init__(self, platform, log_callback=None, product_keyword=None):
                self._confirm_states = {}

            def start_seckill(self, **kwargs):
                return False

        with patch("app.SeckillWorker", FailedWorker):
            run_seckill_task(task_id, "tb", "2026-06-23 20:00:00")

        self.assertEqual(task_manager.get_task(task_id)["status"], "failed")


if __name__ == "__main__":
    unittest.main()
