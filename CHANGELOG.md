# Changelog

## [1.1.0](https://github.com/Peczenko/3d-packing-backend/compare/v1.0.0...v1.1.0) (2026-08-16)


### Features

* packing pipeline ([#39](https://github.com/Peczenko/3d-packing-backend/issues/39)) ([fea2483](https://github.com/Peczenko/3d-packing-backend/commit/fea248326f0b4ea6952b1f7d567baf2649ec750b))


### Refactoring

* apply new code style ([#40](https://github.com/Peczenko/3d-packing-backend/issues/40)) ([4adf51f](https://github.com/Peczenko/3d-packing-backend/commit/4adf51f251a7eb4a9c997418871ca9cbb54db333))


### Chores & Dependencies

* release v1.0.0 ([#37](https://github.com/Peczenko/3d-packing-backend/issues/37)) ([009ea11](https://github.com/Peczenko/3d-packing-backend/commit/009ea11f579dd3a1741efa5057262b397d1eed87))

## [1.0.0](https://github.com/Peczenko/3d-packing-backend/compare/v0.0.5...v1.0.0) (2026-07-31)


### ⚠ BREAKING CHANGES

* projects and file management ([#27](https://github.com/Peczenko/3d-packing-backend/issues/27))

### Features

* add email notifications and server error alerting ([#26](https://github.com/Peczenko/3d-packing-backend/issues/26)) ([8a564eb](https://github.com/Peczenko/3d-packing-backend/commit/8a564eb432688dc0e90ed3e13b91b5f7bb4b09dd))
* expose build version on /actuator/info ([#22](https://github.com/Peczenko/3d-packing-backend/issues/22)) ([e2f9e32](https://github.com/Peczenko/3d-packing-backend/commit/e2f9e3208e6913f527847db5de6566952529c032))
* jooq runtime settings ([#33](https://github.com/Peczenko/3d-packing-backend/issues/33)) ([f716af6](https://github.com/Peczenko/3d-packing-backend/commit/f716af6db5bab1650500f53df140e7c465802567))
* projects and file management ([#27](https://github.com/Peczenko/3d-packing-backend/issues/27)) ([c725bd6](https://github.com/Peczenko/3d-packing-backend/commit/c725bd6bd782af9274ee73a3ef556b45c635d65f))


### Bug Fixes

* exact constraint violation mapping ([#29](https://github.com/Peczenko/3d-packing-backend/issues/29)) ([de858de](https://github.com/Peczenko/3d-packing-backend/commit/de858de7b959cb2d34e694bf4719297604fe72d6))
* fix duplicated buildInfo() gradle task ([8cdd232](https://github.com/Peczenko/3d-packing-backend/commit/8cdd232940d94acb74c440ba7e2672f2b123035f))
* secret hygiene and alert leak ([#28](https://github.com/Peczenko/3d-packing-backend/issues/28)) ([1924e00](https://github.com/Peczenko/3d-packing-backend/commit/1924e008fe50a833b597424352266d9893df9c92))


### Refactoring

* paging takes the order separately ([#32](https://github.com/Peczenko/3d-packing-backend/issues/32)) ([d5560e3](https://github.com/Peczenko/3d-packing-backend/commit/d5560e3cc9898c43e2c2fbd610423b423c93970f))


### Build System

* generate jooq from postgres ([#34](https://github.com/Peczenko/3d-packing-backend/issues/34)) ([1e5c8fc](https://github.com/Peczenko/3d-packing-backend/commit/1e5c8fc4fc324620749f26bcd449fb53afcc6240))
* make jooqCodegen up-to-date and cacheable ([#31](https://github.com/Peczenko/3d-packing-backend/issues/31)) ([3dad2e9](https://github.com/Peczenko/3d-packing-backend/commit/3dad2e987dff00a01cd39234182fb605feeb7f2d))


### Chores & Dependencies

* resync master with develop ([2dc8ec3](https://github.com/Peczenko/3d-packing-backend/commit/2dc8ec3a8c0b432f41f324666428838db3a18ada))

## [0.0.5](https://github.com/Peczenko/3d-packing-backend/compare/v0.0.4...v0.0.5) (2026-07-25)


### Bug Fixes

* target release-please at master instead of the default branch ([70a9a51](https://github.com/Peczenko/3d-packing-backend/commit/70a9a5164e0ebd0542fa01f2f3a6f93072ed5638))
* target release-please at master instead of the default branch ([f94c0fd](https://github.com/Peczenko/3d-packing-backend/commit/f94c0fdd31d3ce25c45445bf30bb655d89456010))


### Refactoring

* add lombok and refactor code ([#16](https://github.com/Peczenko/3d-packing-backend/issues/16)) ([cc3c8b2](https://github.com/Peczenko/3d-packing-backend/commit/cc3c8b2e5e22ff535c934fe717f86dc744949c5d))


### Chores & Dependencies

* automate releases with release-please ([#18](https://github.com/Peczenko/3d-packing-backend/issues/18)) ([e70f7f7](https://github.com/Peczenko/3d-packing-backend/commit/e70f7f74ecb9e6cd6dda04deb0a357475665b82b))
* reconcile master's 0.0.4 squash into dev ([1cba4b7](https://github.com/Peczenko/3d-packing-backend/commit/1cba4b758859e4d291939c750a20a85b336e36a6))
