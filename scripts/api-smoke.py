#!/usr/bin/env python3
"""Smoke test against a running local demo; moves 1 cent from Alice to Bob."""
import os,json,urllib.request,urllib.parse,http.cookiejar,uuid,datetime
base=os.environ.get('BASE_URL','http://localhost:4200').rstrip('/')+'/api/v1'
password=os.environ['DEMO_PASSWORD']
client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def request(path,body=None,headers=None):
 req=urllib.request.Request(base+path,data=body,headers=headers or {})
 with client.open(req,timeout=30) as response:
  raw=response.read();return json.loads(raw) if raw else None
csrf=request('/auth/csrf');headers={csrf['headerName']:csrf['token'],'Content-Type':'application/x-www-form-urlencoded'}
request('/auth/login',urllib.parse.urlencode({'email':'alice@demo.local','password':password}).encode(),headers)
csrf=request('/auth/csrf');headers={csrf['headerName']:csrf['token'],'Content-Type':'application/json'}
accounts=request('/accounts');beneficiaries=request('/beneficiaries');assert accounts and beneficiaries
source=next(a for a in accounts if a['kind']=='CHECKING' and a['status']=='ACTIVE')
key=str(uuid.uuid4());headers['Idempotency-Key']=key
payload=json.dumps({'sourceAccountId':source['id'],'beneficiaryId':beneficiaries[0]['id'],'amountMinor':1,'currency':'USD','memo':'Smoke test'}).encode()
first=request('/transfers',payload,headers);again=request('/transfers',payload,headers)
assert first==again and first['status']=='POSTED'
updated=request('/accounts');after=next(a for a in updated if a['id']==source['id']);assert after['balanceMinor']==source['balanceMinor']-1
history=request('/accounts/'+source['id']+'/transactions?limit=1');assert history['items']
if history['nextCursor']:request('/accounts/'+source['id']+'/transactions?limit=1&cursor='+urllib.parse.quote(history['nextCursor']))
print('Session login, CSRF, transfer, idempotency, balances and history passed.')
