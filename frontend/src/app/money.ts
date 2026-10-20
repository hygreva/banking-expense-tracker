// Decimal input is parsed as text, avoiding floating point rounding of money.
export function minorUnits(value:string):number {
 if(!/^\d{1,8}(\.\d{1,2})?$/.test(value))throw new Error('Enter a positive amount with at most two decimal places.');
 const [whole,fraction='']=value.split('.');const result=Number(whole)*100+Number(fraction.padEnd(2,'0'));
 if(!Number.isSafeInteger(result)||result<=0)throw new Error('Amount must be positive.');return result;
}
