import test from 'node:test';
import assert from 'node:assert/strict';
import { linkNavigationTarget } from '../src/app/link-navigation.js';

test('내부 링크 이동 시 Query와 Fragment를 보존한다', () => {
    assert.equal(linkNavigationTarget({
        pathname: '/shop',
        search: '?itemId=1',
        hash: '#details',
    }), '/shop?itemId=1#details');
});
