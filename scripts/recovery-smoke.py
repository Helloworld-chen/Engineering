#!/usr/bin/env python3
"""HTTP half of test-restart.sh, against an isolated temporary database."""
import sys,json,urllib.request,urllib.error,http.cookiejar,uuid,base64,time
from pathlib import Path
from datetime import datetime,timedelta
url,phase,statefile=sys.argv[1:]
class Client:
 def __init__(self,user):
  self.open=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
  self.csrf=self.get('/session')['csrfToken']
  for attempt in range(40):
   try:self.post('/auth/login',{'username':user,'password':'Campus123!'});break
   except urllib.error.HTTPError as e:
    if e.code!=503:raise
    time.sleep(.25)
  else:raise RuntimeError('Recovery did not finish')
  self.csrf=self.get('/session')['csrfToken']
 def get(self,path):
  with self.open.open(url+'/api/v1'+path,timeout=10) as r:return json.load(r)
 def post(self,path,body):
  request=urllib.request.Request(url+'/api/v1'+path,data=json.dumps(body).encode(),headers={'Content-Type':'application/json','X-CSRF-TOKEN':self.csrf,'Idempotency-Key':str(uuid.uuid4())})
  with self.open.open(request,timeout=10) as r:return json.load(r)
 def upload(self,purpose):
  boundary='test'+uuid.uuid4().hex
  png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII=')
  body=(f'--{boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\n{purpose}\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="test.png"\r\nContent-Type: image/png\r\n\r\n').encode()+png+f'\r\n--{boundary}--\r\n'.encode()
  req=urllib.request.Request(url+'/api/v1/files',data=body,headers={'Content-Type':'multipart/form-data; boundary='+boundary,'X-CSRF-TOKEN':self.csrf})
  with self.open.open(req,timeout=10) as r:return json.load(r)['id']
if phase=='prepare':
 buyer=Client('buyer');seller=Client('seller')
 order=buyer.post('/orders',{'productId':'demo-product-0'})
 buyer.post('/orders/'+order['id']+'/payment',{})
 shipped=seller.post('/orders/'+order['id']+'/shipment',{'trackingNumber':'RESTART-001','goods':[seller.upload('GOODS')],'packaging':[seller.upload('PACKAGING')]})
 assert shipped['status']=='IN_TRANSIT'
 Path(statefile).write_text(json.dumps({'id':order['id'],'dueAt':shipped['shipments'][0]['dueAt']}))
 print('Prepared paid shipment; backend will be stopped before delivery.')
else:
 state=json.loads(Path(statefile).read_text());buyer=Client('buyer');seller=Client('seller')
 order=buyer.get('/orders/'+state['id'])
 assert order['status']=='COMPLETED',order['status']
 assert order['fundsStatus']=='SETTLED'
 assert order['shipments'][0]['deliveredAt']==state['dueAt']
 expected=datetime.fromisoformat(state['dueAt'].replace('Z','+00:00'))+timedelta(hours=48)
 assert datetime.fromisoformat(order['receiptDeadline'].replace('Z','+00:00'))==expected
 assert buyer.get('/session')['balance']==7200
 assert seller.get('/session')['balance']==17800
 print('PASS: real process restart caught up delivery + receipt, preserved original deadlines and settled once.')
