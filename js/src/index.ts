import morphdom from 'morphdom';

var el1 = document.createElement('div');
el1.className = 'foo';

var el2 = document.createElement('div');
el2.className = 'bar';

morphdom(el1, el2);

console.log("ok");
