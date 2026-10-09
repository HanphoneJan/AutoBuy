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
        if "el.disabled" in script:
            return False
        if "el.checked" in script:
            return self.selected
        if "const nodes = Array.from" in script and "去结算" in script:
            return self.checkout if self.selected else None
        return "complete"

    def find_elements(self, by, value):
        if "checkout-submit" in value and "getOrderInfo" in self.current_url:
            return [self.submit]
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

    def test_jd_requires_product_keyword(self):
        worker = SeckillWorker("jd")
        worker.driver = FakeJdDriver()
        worker.running = True

        self.assertFalse(worker._perform_jd_seckill())

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
