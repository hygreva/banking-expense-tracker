import test from 'node:test';import assert from 'node:assert/strict';import { readFileSync } from 'node:fs';
// Node supports native TS stripping in the documented Node 24 toolchain.
import { minorUnits } from '../src/app/money.ts';
test('money input preserves exact cents',()=>{assert.equal(minorUnits('0.29'),29);assert.equal(minorUnits('125.5'),12550);assert.equal(minorUnits('100'),10000);});
test('invalid or fractional money is rejected',()=>{for(const v of ['0','-1','1.001','1e3','',' 2','NaN'])assert.throws(()=>minorUnits(v));});
