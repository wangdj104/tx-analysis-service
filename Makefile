.PHONY: demo demo-down build

demo:
	docker compose up --build

demo-down:
	docker compose down

build:
	mvn package
	cd frontend && npm ci && npm run build
